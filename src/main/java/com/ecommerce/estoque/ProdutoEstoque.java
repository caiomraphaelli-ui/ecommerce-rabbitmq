package com.ecommerce.estoque;

import com.ecommerce.common.Produto;

/** Produto do catálogo com a quantidade disponível, como é persistido e exposto pelo Estoque. */
public class ProdutoEstoque {
    public int id;
    public String nome;
    public String categoria;
    public double preco;
    public int quantidade;

    public ProdutoEstoque() {
    }

    public ProdutoEstoque(Produto produto, int quantidade) {
        this.id = produto.id;
        this.nome = produto.nome;
        this.categoria = produto.categoria;
        this.preco = produto.preco;
        this.quantidade = quantidade;
    }

    ProdutoEstoque copia() {
        ProdutoEstoque c = new ProdutoEstoque();
        c.id = id;
        c.nome = nome;
        c.categoria = categoria;
        c.preco = preco;
        c.quantidade = quantidade;
        return c;
    }
}
