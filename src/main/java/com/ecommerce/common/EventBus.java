package com.ecommerce.common;

import com.google.gson.Gson;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Delivery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * Camada fina sobre o Channel do RabbitMQ que cuida de:
 *  - montar o Envelope, gerar o hash/assinatura digital e publicar;
 *  - receber uma entrega (Delivery), validar o evento (produtor autorizado e
 *    assinatura digital) e devolver o payload já desserializado — ou marcá-lo
 *    como inválido, caso em que o evento deve ser descartado.
 */
public final class EventBus {

    private static final Gson GSON = new Gson();

    private EventBus() {
    }

    /** Publica um evento assinado digitalmente com a chave privada do produtor. */
    public static void publicar(Channel channel, String exchange, String routingKey,
                                 String producerName, Object payloadObj, PrivateKey chavePrivada) throws IOException {
        String payloadJson = GSON.toJson(payloadObj);
        Envelope envelope = new Envelope(exchange, routingKey, producerName, payloadJson);
        envelope.signature = CryptoUtils.assinar(envelope.conteudoAssinado(), chavePrivada);

        String envelopeJson = GSON.toJson(envelope);
        channel.basicPublish(
                exchange,
                routingKey,
                new AMQP.BasicProperties.Builder()
                        .contentType("application/json")
                        .deliveryMode(2) // persistente
                        .build(),
                envelopeJson.getBytes(StandardCharsets.UTF_8)
        );
    }

    /** Resultado da validação/leitura de um evento recebido. */
    public static class EventoRecebido<T> {
        public final boolean valido;
        public final String motivo;   // motivo da rejeição (null se válido)
        public final Envelope envelope;
        public final T payload;

        EventoRecebido(boolean valido, String motivo, Envelope envelope, T payload) {
            this.valido = valido;
            this.motivo = motivo;
            this.envelope = envelope;
            this.payload = payload;
        }
    }

    private static <T> EventoRecebido<T> invalido(Envelope envelope, String motivo) {
        return new EventoRecebido<>(false, motivo, envelope, null);
    }

    /**
     * Lê o envelope de uma entrega e o valida:
     *  1. o envelope precisa corresponder à exchange/routing key da entrega;
     *  2. o produtor indicado precisa ser o responsável por aquele evento;
     *  3. obtém a chave pública do produtor e verifica a assinatura digital.
     * Só então desserializa o payload. Se qualquer etapa falhar, valido = false
     * e o chamador deve descartar o evento.
     */
    public static <T> EventoRecebido<T> receber(Delivery delivery, KeyStoreManager keyStoreManager, Class<T> payloadClass) {
        Envelope envelope;
        try {
            envelope = GSON.fromJson(new String(delivery.getBody(), StandardCharsets.UTF_8), Envelope.class);
        } catch (RuntimeException e) {
            return invalido(null, "envelope malformado");
        }
        if (envelope == null) {
            return invalido(null, "envelope vazio");
        }

        String exchange = delivery.getEnvelope().getExchange();
        String routingKey = delivery.getEnvelope().getRoutingKey();
        if (!exchange.equals(envelope.exchange) || !routingKey.equals(envelope.routingKey)) {
            return invalido(envelope, "exchange/routing key do envelope não correspondem às da entrega");
        }

        String produtorEsperado = RabbitConfig.produtorAutorizado(routingKey);
        if (produtorEsperado == null || !produtorEsperado.equals(envelope.producer)) {
            return invalido(envelope, "produtor '" + envelope.producer + "' não é o responsável por eventos '" + routingKey + "'");
        }

        PublicKey chavePublica;
        try {
            chavePublica = keyStoreManager.getChavePublicaDe(envelope.producer);
        } catch (IllegalStateException e) {
            return invalido(envelope, e.getMessage());
        }

        if (!CryptoUtils.verificar(envelope.conteudoAssinado(), envelope.signature, chavePublica)) {
            return invalido(envelope, "assinatura digital inválida");
        }

        T payload;
        try {
            payload = GSON.fromJson(envelope.payload, payloadClass);
        } catch (RuntimeException e) {
            return invalido(envelope, "payload malformado");
        }
        if (payload == null) {
            return invalido(envelope, "payload vazio");
        }
        return new EventoRecebido<>(true, null, envelope, payload);
    }
}
