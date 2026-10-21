package com.ecommerce.mockpagamento;

import com.ecommerce.common.Http;
import com.ecommerce.common.HttpConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Sistema externo que simula um provedor de pagamentos: só HTTP, sem RabbitMQ nem chaves. */
public class MockPagamentoApp {

    private final Map<String, Cobranca> cobrancas = new ConcurrentHashMap<>();
    private final String paginaCheckout;

    static class Cobranca {
        String id;
        String pedidoId;
        double valor;
        String webhookUrl;
        String status = "PENDENTE"; // PENDENTE, APROVADO, RECUSADO ou CANCELADO
        long criadaEm = System.currentTimeMillis();
    }

    static class SolicitacaoCobranca {
        String pedidoId;
        double valor;
        String webhookUrl;
    }

    public MockPagamentoApp() throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/mock-pagamento/checkout.html")) {
            if (is == null) {
                throw new IOException("Recurso /mock-pagamento/checkout.html não encontrado (compile o projeto).");
            }
            paginaCheckout = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public void iniciar() throws IOException {
        HttpServer servidor = Http.criarServidor(HttpConfig.PORTA_MOCK_PAGAMENTO);
        Http.rota(servidor, "/api/cobrancas", this::rotaCobrancas);
        Http.rota(servidor, "/checkout", this::rotaCheckout);
        servidor.start();
        System.out.println("Mock de Pagamento (provedor externo) em http://localhost:" + HttpConfig.PORTA_MOCK_PAGAMENTO);
    }

    private void rotaCobrancas(HttpExchange ex) throws Exception {
        String[] partes = Http.subcaminho(ex).split("/");
        if (partes[0].isEmpty()) {
            Http.exigirMetodo(ex, "POST");
            criarCobranca(ex);
            return;
        }
        Cobranca cobranca = buscar(partes[0]);
        if (partes.length == 1) {
            Http.exigirMetodo(ex, "GET");
            Http.responderJson(ex, 200, cobranca);
        } else if (partes.length == 2 && "cancelar".equals(partes[1])) {
            Http.exigirMetodo(ex, "POST");
            synchronized (cobranca) {
                if ("PENDENTE".equals(cobranca.status)) {
                    cobranca.status = "CANCELADO";
                    log("Cobrança " + cobranca.id + " (pedido " + cobranca.pedidoId + ") cancelada pela loja.");
                }
            }
            Http.responderJson(ex, 200, cobranca);
        } else {
            throw new Http.ErroHttp(404, "Rota não encontrada");
        }
    }

    private void criarCobranca(HttpExchange ex) throws IOException {
        SolicitacaoCobranca s = Http.lerJson(ex, SolicitacaoCobranca.class);
        if (s.pedidoId == null || s.webhookUrl == null || s.valor <= 0) {
            throw new Http.ErroHttp(400, "Campos obrigatórios: pedidoId, valor (> 0) e webhookUrl");
        }
        Cobranca c = new Cobranca();
        c.id = "cob_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        c.pedidoId = s.pedidoId;
        c.valor = s.valor;
        c.webhookUrl = s.webhookUrl;
        cobrancas.put(c.id, c);

        String checkoutUrl = "http://localhost:" + HttpConfig.PORTA_MOCK_PAGAMENTO + "/checkout/" + c.id;
        log("Cobrança " + c.id + " criada para o pedido " + c.pedidoId + " (R$ " + String.format("%.2f", c.valor)
                + "). Webhook: " + c.webhookUrl);

        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("cobrancaId", c.id);
        corpo.put("checkoutUrl", checkoutUrl);
        Http.responderJson(ex, 201, corpo);
    }

    private void rotaCheckout(HttpExchange ex) throws Exception {
        String[] partes = Http.subcaminho(ex).split("/");
        if (partes.length == 1 && !partes[0].isEmpty()) {
            Http.exigirMetodo(ex, "GET");
            buscar(partes[0]);
            String html = paginaCheckout.replace("{{COBRANCA_ID}}", partes[0]);
            Http.responder(ex, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
        } else if (partes.length == 2 && ("aprovar".equals(partes[1]) || "recusar".equals(partes[1]))) {
            Http.exigirMetodo(ex, "POST");
            decidir(ex, buscar(partes[0]), "aprovar".equals(partes[1]) ? "APROVADO" : "RECUSADO");
        } else {
            throw new Http.ErroHttp(404, "Rota não encontrada");
        }
    }

    private void decidir(HttpExchange ex, Cobranca cobranca, String status) throws Exception {
        synchronized (cobranca) {
            if (!"PENDENTE".equals(cobranca.status)) {
                throw new Http.ErroHttp(409, "Esta cobrança já está " + cobranca.status);
            }

            Map<String, Object> webhook = new LinkedHashMap<>();
            webhook.put("cobrancaId", cobranca.id);
            webhook.put("pedidoId", cobranca.pedidoId);
            webhook.put("status", status);
            webhook.put("valor", cobranca.valor);
            webhook.put("timestamp", System.currentTimeMillis());

            log("Usuário escolheu " + status + " para a cobrança " + cobranca.id + ". Enviando Webhook para " + cobranca.webhookUrl);
            Http.Resposta resposta;
            try {
                resposta = Http.postJson(cobranca.webhookUrl, webhook);
            } catch (IOException e) {
                log("Falha ao chamar o Webhook: " + e.getMessage());
                throw new Http.ErroHttp(502, "Não foi possível confirmar o pagamento com a loja. Tente novamente.");
            }
            log("Webhook respondeu HTTP " + resposta.status + ": " + resposta.corpo);

            if (resposta.status == 409) {
                cobranca.status = "CANCELADO";
                throw new Http.ErroHttp(409, "O pedido foi cancelado na loja; a cobrança não pode mais ser paga.");
            }
            if (!resposta.ok()) {
                throw new Http.ErroHttp(502, "A loja não confirmou o pagamento. Tente novamente.");
            }
            cobranca.status = status;
        }
        Http.responderJson(ex, 200, cobranca);
    }

    private Cobranca buscar(String id) {
        Cobranca c = cobrancas.get(id);
        if (c == null) {
            throw new Http.ErroHttp(404, "Cobrança " + id + " não encontrada");
        }
        return c;
    }

    private static void log(String mensagem) {
        System.out.println("[Mock Pagamento] " + mensagem);
    }

    public static void main(String[] args) throws Exception {
        new MockPagamentoApp().iniciar();
        Thread.currentThread().join();
    }
}
