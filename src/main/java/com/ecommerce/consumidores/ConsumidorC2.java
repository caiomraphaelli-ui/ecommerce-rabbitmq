package com.ecommerce.consumidores;

import com.ecommerce.common.RabbitConfig;

/**
 * Consumidor de Promoções C2.
 *
 * Registra interesse em TODAS as categorias de produtos, usando o padrão de
 * binding "promocao.categoria.*" (o caractere * substitui exatamente uma
 * palavra na routing key, cobrindo A, B, C ou qualquer categoria futura).
 */
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
