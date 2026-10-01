package com.ecommerce.principal;

import com.ecommerce.common.Http;
import com.ecommerce.common.ItemPedido;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;

/**
 * Pedido como o API Gateway o acompanha. É alterado pela thread do RabbitMQ e
 * lido pelas threads HTTP, por isso todo acesso passa por métodos synchronized.
 */
public class Pedido {

    /** Item com nome e preço, para o frontend exibir. */
    public static class Item {
        public final int produtoId;
        public final String nome;
        public final double precoUnitario;
        public final int quantidade;

        public Item(int produtoId, String nome, double precoUnitario, int quantidade) {
            this.produtoId = produtoId;
            this.nome = nome;
            this.precoUnitario = precoUnitario;
            this.quantidade = quantidade;
        }
    }

    /** Uma mudança de status, para a linha do tempo do pedido. */
    public static class Etapa {
        public final PedidoStatus status;
        public final String descricao;
        public final long timestamp;

        Etapa(PedidoStatus status, String descricao) {
            this.status = status;
            this.descricao = descricao;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private final String id;
    private final String clienteId;
    private final long criadoEm;
    private final List<Item> itens;
    private double valorTotal;
    private PedidoStatus status;
    private String statusDescricao;
    private String checkoutUrl;
    private String notaFiscal;
    private String motivo;
    private final List<Etapa> historico = new ArrayList<>();

    public Pedido(String id, String clienteId, List<Item> itens) {
        this.id = id;
        this.clienteId = clienteId;
        this.criadoEm = System.currentTimeMillis();
        this.itens = itens;
        this.valorTotal = itens.stream().mapToDouble(i -> i.precoUnitario * i.quantidade).sum();
        atualizar(PedidoStatus.CRIADO, null);
    }

    public String getId() {
        return id;
    }

    public String getClienteId() {
        return clienteId;
    }

    public long getCriadoEm() {
        return criadoEm;
    }

    public synchronized PedidoStatus getStatus() {
        return status;
    }

    public List<ItemPedido> itensDoEvento() {
        List<ItemPedido> lista = new ArrayList<>();
        for (Item i : itens) {
            lista.add(new ItemPedido(i.produtoId, i.quantidade));
        }
        return lista;
    }

    /**
     * Muda o status e registra a etapa no histórico. Não altera pedidos já
     * finalizados (ex.: evento atrasado de um pedido cancelado pelo cliente).
     * Devolve false se a mudança foi ignorada.
     */
    public synchronized boolean atualizar(PedidoStatus novo, String motivo) {
        if (status != null && status.finalizado) {
            return false;
        }
        this.status = novo;
        this.statusDescricao = novo.descricao;
        if (motivo != null) {
            this.motivo = motivo;
        }
        historico.add(new Etapa(novo, novo.descricao));
        return true;
    }

    /** Cancela por pedido do cliente, de forma atômica, só se o status ainda permitir. */
    public synchronized boolean cancelarPeloCliente() {
        return status.cancelavel() && atualizar(PedidoStatus.CANCELADO_USUARIO, "Cancelado pelo cliente");
    }

    public synchronized void setValorTotal(double valorTotal) {
        this.valorTotal = valorTotal;
    }

    public synchronized void setCheckoutUrl(String checkoutUrl) {
        this.checkoutUrl = checkoutUrl;
    }

    public synchronized void setNotaFiscal(String notaFiscal) {
        this.notaFiscal = notaFiscal;
    }

    /** Cópia consistente em JSON para responder ao frontend (REST ou SSE). */
    public synchronized JsonElement json() {
        return Http.GSON.toJsonTree(this);
    }
}
