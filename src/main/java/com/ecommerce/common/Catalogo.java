package com.ecommerce.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class Catalogo {

    private static final List<Produto> PRODUTOS = new ArrayList<>();

    static {
        PRODUTOS.add(new Produto(1, "Teclado Mecânico", "A", 250.00));
        PRODUTOS.add(new Produto(2, "Mouse Gamer", "A", 120.00));
        PRODUTOS.add(new Produto(3, "Monitor 24\"", "A", 899.90));
        PRODUTOS.add(new Produto(4, "Camiseta", "B", 59.90));
        PRODUTOS.add(new Produto(5, "Tênis Esportivo", "B", 219.90));
        PRODUTOS.add(new Produto(6, "Jaqueta Corta-Vento", "B", 179.90));
        PRODUTOS.add(new Produto(7, "Livro de Java", "C", 89.90));
        PRODUTOS.add(new Produto(8, "Fone de Ouvido", "C", 149.90));
        PRODUTOS.add(new Produto(9, "Cafeteira Elétrica", "C", 199.90));
    }

    private Catalogo() {
    }

    public static List<Produto> listar() {
        return PRODUTOS;
    }

    public static Optional<Produto> porId(int id) {
        return PRODUTOS.stream().filter(p -> p.id == id).findFirst();
    }

    public static int estoqueInicialPadrao() {
        return 10;
    }
}
