package com.ecommerce.common;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.util.UUID;

/**
 * Envelope que trafega no RabbitMQ. Contém os metadados do evento, o payload
 * (já serializado em JSON, como String, para garantir que a verificação da
 * assinatura seja feita sobre os EXATOS mesmos bytes que foram assinados na
 * origem) e o campo Signature com a assinatura digital (Base64) gerada com a
 * chave privada do microsserviço produtor.
 */
public class Envelope {

    private static final Gson GSON = new Gson();

    public String eventId;
    public String exchange;
    public String routingKey;
    public String producer;
    public long timestamp;
    public String payload;

    @SerializedName("Signature")
    public String signature;

    public Envelope() {
    }

    public Envelope(String exchange, String routingKey, String producer, String payload) {
        this.eventId = UUID.randomUUID().toString();
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.producer = producer;
        this.timestamp = System.currentTimeMillis();
        this.payload = payload;
    }

    /**
     * Conteúdo do evento coberto pela assinatura: todos os campos do envelope,
     * exceto a própria Signature, em ordem fixa. Assim, alterar a routing key,
     * o produtor ou qualquer outro campo invalida a assinatura.
     */
    public String conteudoAssinado() {
        return GSON.toJson(new Object[]{eventId, exchange, routingKey, producer, timestamp, payload});
    }
}
