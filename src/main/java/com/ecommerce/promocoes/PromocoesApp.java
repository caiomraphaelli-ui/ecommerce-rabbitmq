package com.ecommerce.promocoes;

import com.ecommerce.common.*;
import com.rabbitmq.client.BuiltinExchangeType;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Microsserviço Promoções.
 *
 * Gera promoções aleatórias de produtos e publica na exchange Promoções
 * (topic), usando routing keys que indicam a categoria do produto:
 * promocao.categoria.A, promocao.categoria.B, promocao.categoria.C.
 *
 * Este microsserviço apenas publica eventos; não consome nenhum evento e não
 * realiza chamadas para outros microsserviços.
 */
public class PromocoesApp extends ProcessoMensageria {

    private static final int[] DESCONTOS_POSSIVEIS = {10, 15, 20, 25, 30, 40};
    private static final long INTERVALO_MS = 5000;

    public PromocoesApp() throws Exception {
        super("Promoções", RabbitConfig.MS_PROMOCOES, true);
    }

    @Override
    public void iniciar() throws Exception {
        declararExchange(RabbitConfig.EXCHANGE_PROMOCOES, BuiltinExchangeType.TOPIC);

        System.out.println("Microsserviço Promoções iniciado. Publicando promoções periodicamente...");
        while (true) {
            gerarEPublicarPromocao();
            Thread.sleep(INTERVALO_MS);
        }
    }

    private void gerarEPublicarPromocao() throws IOException {
        List<Produto> produtos = Catalogo.listar();
        Produto produto = produtos.get(ThreadLocalRandom.current().nextInt(produtos.size()));
        int desconto = DESCONTOS_POSSIVEIS[ThreadLocalRandom.current().nextInt(DESCONTOS_POSSIVEIS.length)];

        Payloads.Promocao payload = new Payloads.Promocao(produto.id, produto.nome, produto.categoria, desconto);
        String routingKey = RabbitConfig.rkPromocaoCategoria(produto.categoria);

        publicar(RabbitConfig.EXCHANGE_PROMOCOES, routingKey, payload);
        log("Publicada promoção: " + produto.nome + " (categoria " + produto.categoria
                + ") com " + desconto + "% de desconto -> routing key: " + routingKey);
    }

    public static void main(String[] args) throws Exception {
        new PromocoesApp().iniciar();
    }
}
