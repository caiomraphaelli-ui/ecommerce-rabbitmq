package com.ecommerce.common;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.util.UUID;

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

    public String conteudoAssinado() {
        return GSON.toJson(new Object[]{eventId, exchange, routingKey, producer, timestamp, payload});
    }
}
