package com.ecommerce.consumidores;

import com.ecommerce.common.RabbitConfig;

/**
 * Consumidor de Promoções C1.
 *
 * Registra interesse apenas nas categorias de produtos A e B, fazendo o bind
 * da sua fila nas routing keys promocao.categoria.A e promocao.categoria.B.
 */
public class ConsumidorC1 extends ConsumidorPromocoes {

    public ConsumidorC1() throws Exception {
        super("C1", RabbitConfig.CONSUMIDOR_C1, RabbitConfig.FILA_C1, "categorias A e B",
                RabbitConfig.rkPromocaoCategoria("A"),
                RabbitConfig.rkPromocaoCategoria("B"));
    }

    public static void main(String[] args) throws Exception {
        new ConsumidorC1().iniciar();
        Thread.currentThread().join();
    }
}
