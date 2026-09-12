package com.ecommerce.common;

/**
 * Representa um produto do catálogo do e-commerce.
 */
public class Produto {
    public final int id;
    public final String nome;
    public final String categoria; // "A", "B" ou "C"
    public final double preco;

    public Produto(int id, String nome, String categoria, double preco) {
        this.id = id;
        this.nome = nome;
        this.categoria = categoria;
        this.preco = preco;
    }

    @Override
    public String toString() {
        return String.format("[%d] %s (categoria %s) - R$ %.2f", id, nome, categoria, preco);
    }
}
