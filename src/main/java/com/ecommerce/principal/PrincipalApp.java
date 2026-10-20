package com.ecommerce.principal;

import com.ecommerce.common.*;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import com.rabbitmq.client.BuiltinExchangeType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MS Principal / API Gateway.
 *  - expõe a API REST consumida pelo frontend (e serve os arquivos do frontend);
 *  - transforma as ações do usuário em eventos assinados no RabbitMQ
 *    (pedido.criado, pedido.excluido, interesse.promocao);
 *  - consome os eventos de Estoque, Pagamento e Entrega e repassa cada
 *    mudança de status ao navegador do dono do pedido via SSE;
 *  - consulta a lista de produtos diretamente no MS Estoque, via REST.
 *
 * Endpoints:
 *   GET    /api/produtos                       produtos disponíveis em estoque
 *   POST   /api/pedidos                        cria pedido {clienteId, itens:[{produtoId, quantidade}]}
 *   GET    /api/pedidos?clienteId=...          pedidos do cliente
 *   DELETE /api/pedidos/{id}?clienteId=...     cancela um pedido
 *   POST   /api/promocoes/interesse            registra interesse {email, categorias:["A","B"]}
 *   DELETE /api/promocoes/interesse?email=...  cancela o interesse
 *   GET    /api/eventos?clienteId=...          stream SSE com as notificações dos pedidos
 */
public class PrincipalApp extends ProcessoMensageria {

    private static final Set<String> CATEGORIAS = Set.of("A", "B", "C");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int QUANTIDADE_MAXIMA_POR_ITEM = 99;

    private final Map<String, Pedido> pedidos = new ConcurrentHashMap<>();
    private final SseHub sse = new SseHub();

    public PrincipalApp() throws Exception {
        super("Gateway", RabbitConfig.MS_PRINCIPAL, true);
    }

    @Override
    public void iniciar() throws Exception {
        declararExchange(RabbitConfig.EXCHANGE_ECOMMERCE, BuiltinExchangeType.DIRECT);
        declararFila(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.FILA_PRINCIPAL,
                RabbitConfig.RK_PEDIDO_ESTOQUE_OK,
                RabbitConfig.RK_ESTOQUE_INDISPONIVEL,
                RabbitConfig.RK_PAGAMENTO_PENDENTE,
                RabbitConfig.RK_PAGAMENTO_APROVADO,
                RabbitConfig.RK_PAGAMENTO_RECUSADO,
                RabbitConfig.RK_PEDIDO_ENVIADO);

        registrarTratador(RabbitConfig.RK_PEDIDO_ESTOQUE_OK, Payloads.PedidoEstoqueOk.class, this::aoConfirmarEstoque);
        registrarTratador(RabbitConfig.RK_ESTOQUE_INDISPONIVEL, Payloads.EstoqueIndisponivel.class, this::aoFaltarEstoque);
        registrarTratador(RabbitConfig.RK_PAGAMENTO_PENDENTE, Payloads.PagamentoPendente.class, this::aoCriarCobranca);
        registrarTratador(RabbitConfig.RK_PAGAMENTO_APROVADO, Payloads.PagamentoAprovado.class, this::aoAprovarPagamento);
        registrarTratador(RabbitConfig.RK_PAGAMENTO_RECUSADO, Payloads.PagamentoRecusado.class, this::aoRecusarPagamento);
        registrarTratador(RabbitConfig.RK_PEDIDO_ENVIADO, Payloads.PedidoEnviado.class, this::aoEnviarPedido);

        consumir(RabbitConfig.FILA_PRINCIPAL);
        iniciarServidorHttp();
    }

    // ===================== eventos do RabbitMQ -> SSE =====================

    private void aoConfirmarEstoque(Payloads.PedidoEstoqueOk evento) {
        Pedido p = pedidos.get(evento.pedidoId);
        if (p == null) {
            return;
        }
        p.setValorTotal(evento.valorTotal);
        mudarStatus(p, PedidoStatus.ESTOQUE_CONFIRMADO, null, "Estoque confirmado");
    }

    private void aoFaltarEstoque(Payloads.EstoqueIndisponivel evento) throws IOException {
        Pedido p = pedidos.get(evento.pedidoId);
        if (p == null) {
            return;
        }
        if (mudarStatus(p, PedidoStatus.CANCELADO_ESTOQUE, evento.motivo, "Produto indisponível em estoque")) {
            publicarPedidoExcluido(p.getId(), "Produto indisponível em estoque");
        }
    }

    private void aoCriarCobranca(Payloads.PagamentoPendente evento) {
        Pedido p = pedidos.get(evento.pedidoId);
        if (p == null) {
            return;
        }
        p.setCheckoutUrl(evento.checkoutUrl);
        mudarStatus(p, PedidoStatus.AGUARDANDO_PAGAMENTO, null, "Cobrança gerada, aguardando pagamento");
    }

    private void aoAprovarPagamento(Payloads.PagamentoAprovado evento) {
        Pedido p = pedidos.get(evento.pedidoId);
        if (p == null) {
            return;
        }
        mudarStatus(p, PedidoStatus.PAGAMENTO_APROVADO, null, "Pagamento aprovado");
    }

    private void aoRecusarPagamento(Payloads.PagamentoRecusado evento) throws IOException {
        Pedido p = pedidos.get(evento.pedidoId);
        if (p == null) {
            return;
        }
        if (mudarStatus(p, PedidoStatus.PAGAMENTO_RECUSADO, evento.motivo, "Pagamento recusado")) {
            publicarPedidoExcluido(p.getId(), "Pagamento recusado");
        }
    }

    private void aoEnviarPedido(Payloads.PedidoEnviado evento) {
        Pedido p = pedidos.get(evento.pedidoId);
        if (p == null) {
            return;
        }
        p.setNotaFiscal(evento.numeroNota);
        mudarStatus(p, PedidoStatus.ENVIADO, null, "Pedido enviado! Nota fiscal " + evento.numeroNota);
    }

    /** Atualiza o status do pedido e notifica o dono via SSE. Devolve false se o pedido já estava finalizado. */
    private boolean mudarStatus(Pedido p, PedidoStatus status, String motivo, String mensagem) {
        if (!p.atualizar(status, motivo)) {
            log("Pedido " + p.getId() + " já finalizado (" + p.getStatus() + "); evento " + status + " ignorado.");
            return false;
        }
        notificar(p, status, mensagem);
        return true;
    }

    private void notificar(Pedido p, PedidoStatus status, String mensagem) {
        Map<String, Object> notificacao = new LinkedHashMap<>();
        notificacao.put("tipo", status.name());
        notificacao.put("mensagem", "Pedido " + p.getId() + ": " + mensagem);
        notificacao.put("pedido", p.json());
        int abas = sse.enviar(p.getClienteId(), "pedido", notificacao);
        log("Pedido " + p.getId() + " -> " + status + " (notificação SSE entregue a " + abas + " conexão(ões))");
    }

    private void publicarPedidoExcluido(String pedidoId, String motivo) throws IOException {
        publicar(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.RK_PEDIDO_EXCLUIDO,
                new Payloads.PedidoExcluido(pedidoId, motivo));
    }

    // ===================== API REST =====================

    private void iniciarServidorHttp() throws IOException {
        HttpServer servidor = Http.criarServidor(HttpConfig.PORTA_GATEWAY);
        Http.rota(servidor, "/api/produtos", this::rotaProdutos);
        Http.rota(servidor, "/api/pedidos", this::rotaPedidos);
        Http.rota(servidor, "/api/promocoes/interesse", this::rotaInteresse);
        Http.rota(servidor, "/api/eventos", this::rotaEventos);
        Http.rota(servidor, "/", this::rotaFrontend);
        servidor.start();
        System.out.println("API Gateway em http://localhost:" + HttpConfig.PORTA_GATEWAY
                + "  (frontend: http://localhost:" + HttpConfig.PORTA_GATEWAY + "/)");
    }

    /** GET /api/produtos — consulta o MS Estoque via REST. */
    private void rotaProdutos(HttpExchange ex) throws Exception {
        Http.exigirMetodo(ex, "GET");
        Http.responderJson(ex, 200, consultarEstoque(true));
    }

    /** Produto como o MS Estoque o devolve no endpoint REST. */
    private static class ProdutoEstoque {
        int id;
        String nome;
        String categoria;
        double preco;
        int quantidade;
    }

    /** Busca os produtos no MS Estoque (REST). */
    private List<ProdutoEstoque> consultarEstoque(boolean somenteDisponiveis) throws InterruptedException {
        Http.Resposta resposta;
        try {
            resposta = Http.get(HttpConfig.URL_ESTOQUE + "/produtos" + (somenteDisponiveis ? "?disponiveis=true" : ""));
        } catch (IOException e) {
            throw new Http.ErroHttp(503, "MS Estoque indisponível");
        }
        if (!resposta.ok()) {
            throw new Http.ErroHttp(502, "MS Estoque respondeu HTTP " + resposta.status);
        }
        return resposta.comoJson(new TypeToken<List<ProdutoEstoque>>() {}.getType());
    }

    private void rotaPedidos(HttpExchange ex) throws Exception {
        String id = Http.subcaminho(ex);
        if (id.isEmpty()) {
            Http.exigirMetodo(ex, "GET", "POST");
            if ("POST".equals(ex.getRequestMethod())) {
                criarPedido(ex);
            } else {
                listarPedidos(ex);
            }
        } else {
            Http.exigirMetodo(ex, "DELETE");
            cancelarPedido(ex, id);
        }
    }

    private static class NovoPedido {
        String clienteId;
        List<ItemPedido> itens;
    }

    /** POST /api/pedidos — publica pedido.criado. */
    private void criarPedido(HttpExchange ex) throws Exception {
        NovoPedido req = Http.lerJson(ex, NovoPedido.class);
        String clienteId = exigirClienteId(req.clienteId);
        if (req.itens == null || req.itens.isEmpty()) {
            throw new Http.ErroHttp(400, "O pedido precisa ter ao menos um item");
        }

        // soma quantidades repetidas do mesmo produto
        Map<Integer, Integer> quantidades = new LinkedHashMap<>();
        for (ItemPedido item : req.itens) {
            if (item.quantidade <= 0 || item.quantidade > QUANTIDADE_MAXIMA_POR_ITEM) {
                throw new Http.ErroHttp(400, "Quantidade inválida para o produto " + item.produtoId);
            }
            quantidades.merge(item.produtoId, item.quantidade, Integer::sum);
        }

        // nome e preço vêm do MS Estoque (REST); a disponibilidade é decidida por ele via evento
        Map<Integer, ProdutoEstoque> catalogo = consultarEstoque(false).stream()
                .collect(Collectors.toMap(p -> p.id, p -> p));
        List<Pedido.Item> itens = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : quantidades.entrySet()) {
            ProdutoEstoque produto = catalogo.get(e.getKey());
            if (produto == null) {
                throw new Http.ErroHttp(400, "Produto " + e.getKey() + " não existe");
            }
            itens.add(new Pedido.Item(produto.id, produto.nome, produto.preco, e.getValue()));
        }

        String pedidoId = UUID.randomUUID().toString().substring(0, 8);
        Pedido pedido = new Pedido(pedidoId, clienteId, itens);
        pedidos.put(pedidoId, pedido);
        publicar(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.RK_PEDIDO_CRIADO,
                new Payloads.PedidoCriado(pedidoId, pedido.itensDoEvento()));
        log("Pedido " + pedidoId + " criado pelo cliente " + clienteId + " e publicado em pedido.criado");

        Http.responderJson(ex, 201, pedido.json());
    }

    /** GET /api/pedidos?clienteId=... — pedidos do cliente, mais recentes primeiro. */
    private void listarPedidos(HttpExchange ex) throws IOException {
        String clienteId = exigirClienteId(Http.query(ex).get("clienteId"));
        List<JsonElement> lista = pedidos.values().stream()
                .filter(p -> p.getClienteId().equals(clienteId))
                .sorted(Comparator.comparingLong(Pedido::getCriadoEm).reversed())
                .map(Pedido::json)
                .collect(Collectors.toList());
        Http.responderJson(ex, 200, lista);
    }

    /** DELETE /api/pedidos/{id}?clienteId=... — publica pedido.excluido. */
    private void cancelarPedido(HttpExchange ex, String pedidoId) throws IOException {
        String clienteId = exigirClienteId(Http.query(ex).get("clienteId"));
        Pedido p = pedidos.get(pedidoId);
        if (p == null || !p.getClienteId().equals(clienteId)) {
            throw new Http.ErroHttp(404, "Pedido " + pedidoId + " não encontrado");
        }
        if (!p.cancelarPeloCliente()) {
            throw new Http.ErroHttp(409, "Pedido não pode mais ser cancelado (" + p.getStatus().descricao + ")");
        }
        notificar(p, PedidoStatus.CANCELADO_USUARIO, "Cancelado a seu pedido");
        publicarPedidoExcluido(pedidoId, "Cancelado pelo cliente");
        Http.responderJson(ex, 200, p.json());
    }

    private static class NovoInteresse {
        String email;
        List<String> categorias;
    }

    /** POST/DELETE /api/promocoes/interesse — publica interesse.promocao. */
    private void rotaInteresse(HttpExchange ex) throws Exception {
        Http.exigirMetodo(ex, "POST", "DELETE");
        Map<String, Object> resposta = new LinkedHashMap<>();

        if ("POST".equals(ex.getRequestMethod())) {
            NovoInteresse req = Http.lerJson(ex, NovoInteresse.class);
            String email = exigirEmail(req.email);
            List<String> categorias = req.categorias == null ? List.of()
                    : req.categorias.stream().map(c -> c.trim().toUpperCase()).distinct().sorted().collect(Collectors.toList());
            if (categorias.isEmpty() || !CATEGORIAS.containsAll(categorias)) {
                throw new Http.ErroHttp(400, "Escolha ao menos uma categoria entre A, B e C");
            }
            publicar(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.RK_INTERESSE_PROMOCAO,
                    new Payloads.InteressePromocao(Payloads.InteressePromocao.INSCREVER, email, categorias));
            log("Interesse em promoções registrado: " + email + " -> " + categorias);
            resposta.put("email", email);
            resposta.put("categorias", categorias);
            resposta.put("mensagem", "Inscrição enviada. Você receberá as promoções por e-mail.");
        } else {
            String email = exigirEmail(Http.query(ex).get("email"));
            publicar(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.RK_INTERESSE_PROMOCAO,
                    new Payloads.InteressePromocao(Payloads.InteressePromocao.CANCELAR, email, List.of()));
            log("Interesse em promoções cancelado: " + email);
            resposta.put("email", email);
            resposta.put("mensagem", "Inscrição cancelada. Você não receberá mais e-mails de promoções.");
        }
        Http.responderJson(ex, 202, resposta);
    }

    /** GET /api/eventos?clienteId=... — conexão SSE. */
    private void rotaEventos(HttpExchange ex) throws Exception {
        Http.exigirMetodo(ex, "GET");
        sse.abrir(ex, exigirClienteId(Http.query(ex).get("clienteId")));
    }

    private static String exigirClienteId(String clienteId) {
        if (clienteId == null || clienteId.isBlank() || clienteId.length() > 64) {
            throw new Http.ErroHttp(400, "Parâmetro clienteId obrigatório");
        }
        return clienteId.trim();
    }

    private static String exigirEmail(String email) {
        String normalizado = email == null ? "" : email.trim().toLowerCase();
        if (!EMAIL.matcher(normalizado).matches()) {
            throw new Http.ErroHttp(400, "E-mail inválido");
        }
        return normalizado;
    }

    // ===================== arquivos do frontend =====================

    private static final Map<String, String> TIPOS = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "json", "application/json");

    /** Serve os arquivos estáticos (HTML/CSS/JS) da pasta frontend/. */
    private void rotaFrontend(HttpExchange ex) throws IOException {
        Http.exigirMetodo(ex, "GET");
        Path raiz = Paths.get(HttpConfig.PASTA_FRONTEND).toAbsolutePath().normalize();
        String caminho = ex.getRequestURI().getPath();
        Path arquivo = raiz.resolve(caminho.endsWith("/") ? caminho.substring(1) + "index.html" : caminho.substring(1)).normalize();
        if (!arquivo.startsWith(raiz) || !Files.isRegularFile(arquivo)) {
            throw new Http.ErroHttp(404, "Arquivo não encontrado: " + caminho);
        }
        String nome = arquivo.getFileName().toString();
        String extensao = nome.substring(nome.lastIndexOf('.') + 1).toLowerCase();
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        Http.responder(ex, 200, TIPOS.getOrDefault(extensao, "application/octet-stream"), Files.readAllBytes(arquivo));
    }

    public static void main(String[] args) throws Exception {
        new PrincipalApp().iniciar();
        System.out.println("MS Principal / API Gateway iniciado. Aguardando requisições e eventos.");
        Thread.currentThread().join();
    }
}
