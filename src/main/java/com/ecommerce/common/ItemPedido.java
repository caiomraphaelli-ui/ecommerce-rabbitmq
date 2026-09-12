package com.ecommerce.common;

public class ItemPedido {
    public int produtoId;
    public int quantidade;

    public ItemPedido() {
    }

    public ItemPedido(int produtoId, int quantidade) {
        this.produtoId = produtoId;
        this.quantidade = quantidade;
    }
}
