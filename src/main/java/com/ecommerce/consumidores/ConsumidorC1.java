package com.ecommerce.consumidores;

import com.ecommerce.common.RabbitConfig;

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
