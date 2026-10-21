package com.ecommerce.estoque;

import com.ecommerce.common.*;
import com.google.gson.reflect.TypeToken;
import com.rabbitmq.client.BuiltinExchangeType;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class EstoqueApp extends ProcessoMensageria {

    private final ArquivoJson arquivo = new ArquivoJson("estoque.json");
    private DadosEstoque dados;

    private static class DadosEstoque {
        Map<Integer, ProdutoEstoque> produtos = new LinkedHashMap<>();
        Map<String, List<ItemPedido>> reservas = new LinkedHashMap<>();
    }

    public EstoqueApp() throws Exception {
        super("Estoque", RabbitConfig.MS_ESTOQUE, true);
        carregarDados();
    }

    private void carregarDados() throws IOException {
        dados = arquivo.ler(new TypeToken<DadosEstoque>() {}.getType());
        if (dados == null) {
            dados = new DadosEstoque();
            for (Produto p : Catalogo.listar()) {
                dados.produtos.put(p.id, new ProdutoEstoque(p, Catalogo.estoqueInicialPadrao()));
            }
            arquivo.salvar(dados);
            log("Estoque inicial criado a partir do catálogo em " + arquivo.caminho());
        } else {
            log("Estoque carregado de " + arquivo.caminho() + " (" + dados.reservas.size() + " reserva(s) ativa(s))");
        }
    }

    @Override
    public void iniciar() throws Exception {
        declararExchange(RabbitConfig.EXCHANGE_ECOMMERCE, BuiltinExchangeType.DIRECT);
        declararFila(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.FILA_ESTOQUE,
                RabbitConfig.RK_PEDIDO_CRIADO,
                RabbitConfig.RK_PEDIDO_EXCLUIDO);

        registrarTratador(RabbitConfig.RK_PEDIDO_CRIADO, Payloads.PedidoCriado.class, this::tratarPedidoCriado);
        registrarTratador(RabbitConfig.RK_PEDIDO_EXCLUIDO, Payloads.PedidoExcluido.class, this::tratarPedidoExcluido);

        iniciarServidorRest();
        consumir(RabbitConfig.FILA_ESTOQUE);
        System.out.println("Microsserviço Estoque iniciado. Estoque atual: " + resumoEstoque());
    }

    // GET /produtos e GET /produtos?disponiveis=true (só quantidade > 0)
    private void iniciarServidorRest() throws IOException {
        HttpServer servidor = Http.criarServidor(HttpConfig.PORTA_ESTOQUE);
        Http.rota(servidor, "/produtos", ex -> {
            Http.exigirMetodo(ex, "GET");
            boolean somenteDisponiveis = "true".equals(Http.query(ex).get("disponiveis"));
            Http.responderJson(ex, 200, listarProdutos(somenteDisponiveis));
        });
        servidor.start();
        log("API REST do Estoque em http://localhost:" + HttpConfig.PORTA_ESTOQUE + "/produtos");
    }

    private synchronized List<ProdutoEstoque> listarProdutos(boolean somenteDisponiveis) {
        List<ProdutoEstoque> lista = new ArrayList<>();
        for (ProdutoEstoque p : dados.produtos.values()) {
            if (!somenteDisponiveis || p.quantidade > 0) {
                lista.add(p.copia());
            }
        }
        return lista;
    }

    private synchronized void tratarPedidoCriado(Payloads.PedidoCriado pedido) throws IOException {
        log("Recebido pedido.criado " + pedido.pedidoId);
        if (dados.reservas.containsKey(pedido.pedidoId)) {
            log("Pedido " + pedido.pedidoId + " já tem reserva (evento repetido). Ignorado.");
            return;
        }

        boolean disponivel = !pedido.itens.isEmpty();
        for (ItemPedido item : pedido.itens) {
            ProdutoEstoque produto = dados.produtos.get(item.produtoId);
            if (produto == null || item.quantidade <= 0 || produto.quantidade < item.quantidade) {
                disponivel = false;
                break;
            }
        }

        if (disponivel) {
            for (ItemPedido item : pedido.itens) {
                dados.produtos.get(item.produtoId).quantidade -= item.quantidade;
            }
            dados.reservas.put(pedido.pedidoId, pedido.itens);
            arquivo.salvar(dados);
            double valorTotal = calcularValorTotal(pedido.itens);
            log("Pedido " + pedido.pedidoId + ": estoque reservado. Valor total: R$ " + String.format("%.2f", valorTotal)
                    + ". Estoque atual: " + resumoEstoque());
            publicar(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.RK_PEDIDO_ESTOQUE_OK,
                    new Payloads.PedidoEstoqueOk(pedido.pedidoId, valorTotal, pedido.itens));
        } else {
            log("Pedido " + pedido.pedidoId + ": produto(s) indisponível(is).");
            publicar(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.RK_ESTOQUE_INDISPONIVEL,
                    new Payloads.EstoqueIndisponivel(pedido.pedidoId, "Quantidade solicitada indisponível em estoque"));
        }
    }

    private synchronized void tratarPedidoExcluido(Payloads.PedidoExcluido evento) throws IOException {
        String pedidoId = evento.pedidoId;
        List<ItemPedido> itensReservados = dados.reservas.remove(pedidoId);
        if (itensReservados != null) {
            for (ItemPedido item : itensReservados) {
                ProdutoEstoque produto = dados.produtos.get(item.produtoId);
                if (produto != null) {
                    produto.quantidade += item.quantidade;
                }
            }
            arquivo.salvar(dados);
            log("Pedido " + pedidoId + " excluído: itens devolvidos ao estoque. Estoque atual: " + resumoEstoque());
        } else {
            log("Pedido " + pedidoId + " excluído: nenhuma reserva encontrada (nada a devolver).");
        }
    }

    private double calcularValorTotal(List<ItemPedido> itens) {
        double total = 0.0;
        for (ItemPedido item : itens) {
            ProdutoEstoque produto = dados.produtos.get(item.produtoId);
            total += (produto == null ? 0.0 : produto.preco) * item.quantidade;
        }
        return total;
    }

    private String resumoEstoque() {
        return dados.produtos.values().stream()
                .map(p -> p.id + "=" + p.quantidade)
                .collect(Collectors.joining(", ", "{", "}"));
    }

    public static void main(String[] args) throws Exception {
        new EstoqueApp().iniciar();
        Thread.currentThread().join();
    }
}
