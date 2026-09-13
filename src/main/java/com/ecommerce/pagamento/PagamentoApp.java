package com.ecommerce.pagamento;

import com.ecommerce.common.*;
import com.rabbitmq.client.BuiltinExchangeType;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Microsserviço Pagamento.
 *
 * Consome pedido.estoque_ok e simula o processamento do pagamento usando uma
 * variável aleatória. Publica pagamento.aprovado ou pagamento.recusado.
 */
public class PagamentoApp extends ProcessoMensageria {

    private static final double PROBABILIDADE_APROVACAO = 0.7;

    public PagamentoApp() throws Exception {
        super("Pagamento", RabbitConfig.MS_PAGAMENTO, true);
    }

    @Override
    public void iniciar() throws Exception {
        declararExchange(RabbitConfig.EXCHANGE_ECOMMERCE, BuiltinExchangeType.DIRECT);
        declararFila(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.FILA_PAGAMENTO, RabbitConfig.RK_PEDIDO_ESTOQUE_OK);

        registrarTratador(RabbitConfig.RK_PEDIDO_ESTOQUE_OK, Payloads.PedidoEstoqueOk.class, this::processarPagamento);

        System.out.println("Microsserviço Pagamento iniciado.");
        consumir(RabbitConfig.FILA_PAGAMENTO);
    }

    private void processarPagamento(Payloads.PedidoEstoqueOk pedido) throws IOException {
        String pedidoId = pedido.pedidoId;
        double valor = pedido.valorTotal;
        log("Processando pagamento do pedido " + pedidoId + " (R$ " + String.format("%.2f", valor) + ")...");

        boolean aprovado = ThreadLocalRandom.current().nextDouble() < PROBABILIDADE_APROVACAO;

        if (aprovado) {
            log("Pedido " + pedidoId + ": pagamento APROVADO.");
            publicar(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.RK_PAGAMENTO_APROVADO,
                    new Payloads.PagamentoAprovado(pedidoId, valor));
        } else {
            log("Pedido " + pedidoId + ": pagamento RECUSADO.");
            publicar(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.RK_PAGAMENTO_RECUSADO,
                    new Payloads.PagamentoRecusado(pedidoId, "Pagamento recusado pela operadora (simulado)"));
        }
    }

    public static void main(String[] args) throws Exception {
        new PagamentoApp().iniciar();
        Thread.currentThread().join();
    }
}
