package com.ecommerce.consumidores;

import com.ecommerce.common.RabbitConfig;

public class ConsumidorC2 extends ConsumidorPromocoes {

    public ConsumidorC2() throws Exception {
        super("C2", RabbitConfig.CONSUMIDOR_C2, RabbitConfig.FILA_C2,
                "todas as categorias (binding key: " + RabbitConfig.BK_PROMOCAO_C2_TODAS + ")",
                RabbitConfig.BK_PROMOCAO_C2_TODAS);
    }

    public static void main(String[] args) throws Exception {
        new ConsumidorC2().iniciar();
        Thread.currentThread().join();
    }
}
