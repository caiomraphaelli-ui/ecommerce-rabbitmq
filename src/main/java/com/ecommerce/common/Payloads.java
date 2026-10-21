package com.ecommerce.common;

import java.util.List;

public final class Payloads {

    private Payloads() {
    }

    /** Publicado pelo MS Principal na routing key pedido.criado */
    public static class PedidoCriado {
        public String pedidoId;
        public List<ItemPedido> itens;

        public PedidoCriado(String pedidoId, List<ItemPedido> itens) {
            this.pedidoId = pedidoId;
            this.itens = itens;
        }
    }

    /** Publicado pelo MS Principal na routing key pedido.excluido */
    public static class PedidoExcluido {
        public String pedidoId;
        public String motivo;

        public PedidoExcluido(String pedidoId, String motivo) {
            this.pedidoId = pedidoId;
            this.motivo = motivo;
        }
    }

    /** Publicado pelo MS Estoque na routing key pedido.estoque_ok */
    public static class PedidoEstoqueOk {
        public String pedidoId;
        public double valorTotal;
        public List<ItemPedido> itens;

        public PedidoEstoqueOk(String pedidoId, double valorTotal, List<ItemPedido> itens) {
            this.pedidoId = pedidoId;
            this.valorTotal = valorTotal;
            this.itens = itens;
        }
    }

    /** Publicado pelo MS Estoque na routing key estoque.indisponivel */
    public static class EstoqueIndisponivel {
        public String pedidoId;
        public String motivo;

        public EstoqueIndisponivel(String pedidoId, String motivo) {
            this.pedidoId = pedidoId;
            this.motivo = motivo;
        }
    }

    /** Publicado pelo MS Principal na routing key interesse.promocao */
    public static class InteressePromocao {
        public static final String INSCREVER = "INSCREVER";
        public static final String CANCELAR = "CANCELAR";

        public String acao;
        public String email;
        public List<String> categorias;

        public InteressePromocao(String acao, String email, List<String> categorias) {
            this.acao = acao;
            this.email = email;
            this.categorias = categorias;
        }
    }

    /** Publicado pelo MS Pagamento na routing key pagamento.pendente */
    public static class PagamentoPendente {
        public String pedidoId;
        public double valorTotal;
        public String checkoutUrl;

        public PagamentoPendente(String pedidoId, double valorTotal, String checkoutUrl) {
            this.pedidoId = pedidoId;
            this.valorTotal = valorTotal;
            this.checkoutUrl = checkoutUrl;
        }
    }

    /** Publicado pelo MS Pagamento na routing key pagamento.aprovado */
    public static class PagamentoAprovado {
        public String pedidoId;
        public double valorTotal;

        public PagamentoAprovado(String pedidoId, double valorTotal) {
            this.pedidoId = pedidoId;
            this.valorTotal = valorTotal;
        }
    }

    /** Publicado pelo MS Pagamento na routing key pagamento.recusado */
    public static class PagamentoRecusado {
        public String pedidoId;
        public String motivo;

        public PagamentoRecusado(String pedidoId, String motivo) {
            this.pedidoId = pedidoId;
            this.motivo = motivo;
        }
    }

    /** Publicado pelo MS Entrega na routing key pedido.enviado */
    public static class PedidoEnviado {
        public String pedidoId;
        public String numeroNota;

        public PedidoEnviado(String pedidoId, String numeroNota) {
            this.pedidoId = pedidoId;
            this.numeroNota = numeroNota;
        }
    }

    /** Publicado pelo MS Promoções nas routing keys promocao.categoria.<X> */
    public static class Promocao {
        public int produtoId;
        public String nomeProduto;
        public String categoria;
        public int descontoPercentual;

        public Promocao(int produtoId, String nomeProduto, String categoria, int descontoPercentual) {
            this.produtoId = produtoId;
            this.nomeProduto = nomeProduto;
            this.categoria = categoria;
            this.descontoPercentual = descontoPercentual;
        }
    }
}
