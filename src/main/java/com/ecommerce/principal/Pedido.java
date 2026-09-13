package com.ecommerce.principal;

import com.ecommerce.common.ItemPedido;

import java.util.List;

public class Pedido {

    public final String id;
    public final List<ItemPedido> itens;
    public double valorTotal;
    public PedidoStatus status;

    public Pedido(String id, List<ItemPedido> itens) {
        this.id = id;
        this.itens = itens;
        this.status = PedidoStatus.CRIADO;
        this.valorTotal = 0.0;
    }
}
