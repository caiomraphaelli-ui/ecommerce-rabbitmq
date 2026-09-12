package com.ecommerce.principal;

import com.ecommerce.common.ItemPedido;

import java.util.List;

public class Pedido {

    public enum Status {
        CRIADO("Criado, aguardando verificação de estoque"),
        AGUARDANDO_PAGAMENTO("Estoque OK, aguardando pagamento"),
        PAGAMENTO_APROVADO("Pagamento aprovado, aguardando envio"),
        ENVIADO("Enviado"),
        CANCELADO_ESTOQUE("Cancelado: produto indisponível em estoque"),
        CANCELADO_PAGAMENTO("Cancelado: pagamento recusado"),
        EXCLUIDO_PELO_USUARIO("Excluído pelo usuário");

        public final String descricao;

        Status(String descricao) {
            this.descricao = descricao;
        }
    }

    public final String id;
    public final List<ItemPedido> itens;
    public double valorTotal;
    public Status status;

    public Pedido(String id, List<ItemPedido> itens) {
        this.id = id;
        this.itens = itens;
        this.status = Status.CRIADO;
        this.valorTotal = 0.0;
    }
}
