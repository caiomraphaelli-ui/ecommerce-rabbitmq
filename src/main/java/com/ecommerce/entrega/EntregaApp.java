package com.ecommerce.entrega;

import com.ecommerce.common.*;
import com.rabbitmq.client.BuiltinExchangeType;

import java.io.IOException;
import java.util.UUID;

/**
 * Microsserviço Entrega.
 *
 * Consome pagamento.aprovado, simula a emissão da nota fiscal e a preparação
 * da entrega, e publica pedido.enviado.
 */
public class EntregaApp extends ProcessoMensageria {

    public EntregaApp() throws Exception {
        super("Entrega", RabbitConfig.MS_ENTREGA, true);
    }

    @Override
    public void iniciar() throws Exception {
        declararExchange(RabbitConfig.EXCHANGE_ECOMMERCE, BuiltinExchangeType.DIRECT);
        declararFila(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.FILA_ENTREGA, RabbitConfig.RK_PAGAMENTO_APROVADO);

        registrarTratador(RabbitConfig.RK_PAGAMENTO_APROVADO, Payloads.PagamentoAprovado.class, this::emitirNotaEEnviar);

        System.out.println("Microsserviço Entrega iniciado.");
        consumir(RabbitConfig.FILA_ENTREGA);
    }

    private void emitirNotaEEnviar(Payloads.PagamentoAprovado pagamento) throws IOException {
        String pedidoId = pagamento.pedidoId;
        String numeroNota = "NF-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        log("Emitindo nota fiscal " + numeroNota + " e preparando entrega do pedido " + pedidoId + "...");

        publicar(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.RK_PEDIDO_ENVIADO,
                new Payloads.PedidoEnviado(pedidoId, numeroNota));
        log("Pedido " + pedidoId + " enviado.");
    }

    public static void main(String[] args) throws Exception {
        new EntregaApp().iniciar();
        Thread.currentThread().join();
    }
}
