package com.ecommerce.pagamento;

import com.ecommerce.common.*;
import com.rabbitmq.client.BuiltinExchangeType;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PagamentoApp extends ProcessoMensageria {

    private final Map<String, Cobranca> cobrancasPorPedido = new ConcurrentHashMap<>();
    // o pedido.excluido pode chegar antes do pedido.estoque_ok
    private final Set<String> pedidosExcluidos = ConcurrentHashMap.newKeySet();

    private static class Cobranca {
        final String pedidoId;
        final String cobrancaId;
        final double valor;
        String status = "PENDENTE"; // PENDENTE, APROVADO, RECUSADO ou CANCELADO

        Cobranca(String pedidoId, String cobrancaId, double valor) {
            this.pedidoId = pedidoId;
            this.cobrancaId = cobrancaId;
            this.valor = valor;
        }
    }

    private static class SolicitacaoCobranca {
        String pedidoId;
        double valor;
        String webhookUrl;

        SolicitacaoCobranca(String pedidoId, double valor, String webhookUrl) {
            this.pedidoId = pedidoId;
            this.valor = valor;
            this.webhookUrl = webhookUrl;
        }
    }

    private static class CobrancaCriada {
        String cobrancaId;
        String checkoutUrl;
    }

    private static class NotificacaoWebhook {
        String cobrancaId;
        String pedidoId;
        String status;
    }

    public PagamentoApp() throws Exception {
        super("Pagamento", RabbitConfig.MS_PAGAMENTO, true);
    }

    @Override
    public void iniciar() throws Exception {
        declararExchange(RabbitConfig.EXCHANGE_ECOMMERCE, BuiltinExchangeType.DIRECT);
        declararFila(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.FILA_PAGAMENTO,
                RabbitConfig.RK_PEDIDO_ESTOQUE_OK,
                RabbitConfig.RK_PEDIDO_EXCLUIDO);

        registrarTratador(RabbitConfig.RK_PEDIDO_ESTOQUE_OK, Payloads.PedidoEstoqueOk.class, this::criarCobranca);
        registrarTratador(RabbitConfig.RK_PEDIDO_EXCLUIDO, Payloads.PedidoExcluido.class, this::cancelarCobranca);

        iniciarServidorWebhook();
        consumir(RabbitConfig.FILA_PAGAMENTO);
        System.out.println("Microsserviço Pagamento iniciado. Provedor de pagamento: " + HttpConfig.URL_MOCK_PAGAMENTO);
    }

    private void criarCobranca(Payloads.PedidoEstoqueOk pedido) throws Exception {
        String pedidoId = pedido.pedidoId;
        if (cobrancasPorPedido.containsKey(pedidoId)) {
            log("Pedido " + pedidoId + " já possui cobrança (evento repetido). Ignorado.");
            return;
        }
        if (pedidosExcluidos.contains(pedidoId)) {
            log("Pedido " + pedidoId + " já foi excluído. Nenhuma cobrança criada.");
            return;
        }
        log("Solicitando cobrança do pedido " + pedidoId + " (R$ " + String.format("%.2f", pedido.valorTotal)
                + ") ao provedor, webhook: " + HttpConfig.URL_WEBHOOK_PAGAMENTO);

        Http.Resposta resposta;
        try {
            resposta = Http.postJson(HttpConfig.URL_MOCK_PAGAMENTO + "/api/cobrancas",
                    new SolicitacaoCobranca(pedidoId, pedido.valorTotal, HttpConfig.URL_WEBHOOK_PAGAMENTO));
        } catch (IOException e) {
            resposta = null;
            log("Provedor de pagamento inacessível: " + e.getMessage());
        }
        if (resposta == null || !resposta.ok()) {
            log("Pedido " + pedidoId + ": não foi possível criar a cobrança. Pagamento RECUSADO.");
            publicar(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.RK_PAGAMENTO_RECUSADO,
                    new Payloads.PagamentoRecusado(pedidoId, "Provedor de pagamento indisponível"));
            return;
        }

        CobrancaCriada criada = resposta.comoJson(CobrancaCriada.class);
        cobrancasPorPedido.put(pedidoId, new Cobranca(pedidoId, criada.cobrancaId, pedido.valorTotal));
        log("Pedido " + pedidoId + ": cobrança " + criada.cobrancaId + " criada. Checkout: " + criada.checkoutUrl);
        publicar(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.RK_PAGAMENTO_PENDENTE,
                new Payloads.PagamentoPendente(pedidoId, pedido.valorTotal, criada.checkoutUrl));
    }

    private void cancelarCobranca(Payloads.PedidoExcluido evento) {
        Cobranca cobranca = cobrancasPorPedido.get(evento.pedidoId);
        if (cobranca == null) {
            pedidosExcluidos.add(evento.pedidoId);
            return;
        }
        synchronized (cobranca) {
            if (!"PENDENTE".equals(cobranca.status)) {
                return;
            }
            cobranca.status = "CANCELADO";
        }
        log("Pedido " + evento.pedidoId + " excluído: cobrança " + cobranca.cobrancaId + " cancelada.");
        try {
            Http.postJson(HttpConfig.URL_MOCK_PAGAMENTO + "/api/cobrancas/" + cobranca.cobrancaId + "/cancelar", Map.of());
        } catch (Exception e) {
            log("Não foi possível avisar o provedor sobre o cancelamento: " + e.getMessage());
        }
    }

    private void iniciarServidorWebhook() throws IOException {
        HttpServer servidor = Http.criarServidor(HttpConfig.PORTA_PAGAMENTO);
        Http.rota(servidor, "/webhook/pagamento", ex -> {
            Http.exigirMetodo(ex, "POST");
            NotificacaoWebhook notificacao = Http.lerJson(ex, NotificacaoWebhook.class);
            log("Webhook recebido: pedido " + notificacao.pedidoId + ", cobrança " + notificacao.cobrancaId
                    + ", status " + notificacao.status);
            String resultado = processarWebhook(notificacao);
            Map<String, Object> corpo = new LinkedHashMap<>();
            corpo.put("recebido", true);
            corpo.put("resultado", resultado);
            Http.responderJson(ex, 200, corpo);
        });
        servidor.start();
        log("Webhook do Pagamento em " + HttpConfig.URL_WEBHOOK_PAGAMENTO);
    }

    private String processarWebhook(NotificacaoWebhook n) throws IOException {
        if (n.pedidoId == null || n.cobrancaId == null || n.status == null) {
            throw new Http.ErroHttp(400, "Campos obrigatórios: cobrancaId, pedidoId, status");
        }
        if (!"APROVADO".equals(n.status) && !"RECUSADO".equals(n.status)) {
            throw new Http.ErroHttp(400, "Status deve ser APROVADO ou RECUSADO");
        }
        Cobranca cobranca = cobrancasPorPedido.get(n.pedidoId);
        if (cobranca == null || !cobranca.cobrancaId.equals(n.cobrancaId)) {
            throw new Http.ErroHttp(404, "Cobrança não encontrada para este pedido");
        }

        synchronized (cobranca) {
            if ("CANCELADO".equals(cobranca.status)) {
                throw new Http.ErroHttp(409, "Pedido cancelado: a cobrança não pode mais ser paga");
            }
            if (!"PENDENTE".equals(cobranca.status)) {
                log("Webhook repetido para o pedido " + n.pedidoId + " (já " + cobranca.status + "). Ignorado.");
                return "JA_PROCESSADO";
            }
            cobranca.status = n.status;
        }

        if ("APROVADO".equals(n.status)) {
            log("Pedido " + n.pedidoId + ": pagamento APROVADO.");
            publicar(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.RK_PAGAMENTO_APROVADO,
                    new Payloads.PagamentoAprovado(n.pedidoId, cobranca.valor));
        } else {
            log("Pedido " + n.pedidoId + ": pagamento RECUSADO.");
            publicar(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.RK_PAGAMENTO_RECUSADO,
                    new Payloads.PagamentoRecusado(n.pedidoId, "Pagamento recusado pelo provedor"));
        }
        return n.status;
    }

    public static void main(String[] args) throws Exception {
        new PagamentoApp().iniciar();
        Thread.currentThread().join();
    }
}
