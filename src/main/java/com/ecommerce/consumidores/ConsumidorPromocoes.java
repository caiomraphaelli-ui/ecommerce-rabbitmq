package com.ecommerce.consumidores;

import com.ecommerce.common.*;
import com.rabbitmq.client.BuiltinExchangeType;

/**
 * Lógica comum dos consumidores de promoções. Cada consumidor cria sua própria
 * fila, associa-a à exchange Promoções com as binding keys das categorias de
 * interesse e exibe as promoções recebidas.
 *
 * Esses processos não realizam chamadas aos microsserviços: apenas consomem
 * eventos de promoções do RabbitMQ (e não publicam nada, por isso não têm
 * chave privada).
 */
public abstract class ConsumidorPromocoes extends ProcessoMensageria {

    private final String fila;
    private final String descricaoInteresse;
    private final String[] bindingKeys;

    protected ConsumidorPromocoes(String nome, String identificador, String fila,
                                  String descricaoInteresse, String... bindingKeys) throws Exception {
        super(nome, identificador, false);
        this.fila = fila;
        this.descricaoInteresse = descricaoInteresse;
        this.bindingKeys = bindingKeys;
    }

    @Override
    public void iniciar() throws Exception {
        declararExchange(RabbitConfig.EXCHANGE_PROMOCOES, BuiltinExchangeType.TOPIC);
        declararFila(RabbitConfig.EXCHANGE_PROMOCOES, fila, bindingKeys);
        for (String bindingKey : bindingKeys) {
            registrarTratador(bindingKey, Payloads.Promocao.class, this::exibirPromocao);
        }

        System.out.println("Consumidor " + nome + " iniciado. Interesse: " + descricaoInteresse + ".");
        consumir(fila);
    }

    private void exibirPromocao(Payloads.Promocao promocao) {
        log("Promoção recebida (" + RabbitConfig.rkPromocaoCategoria(promocao.categoria) + "): "
                + promocao.nomeProduto + " - " + promocao.descontoPercentual + "% OFF (categoria " + promocao.categoria + ")");
    }
}
