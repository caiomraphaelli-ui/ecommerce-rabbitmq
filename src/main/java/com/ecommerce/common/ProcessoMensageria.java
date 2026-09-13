package com.ecommerce.common;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public abstract class ProcessoMensageria {

    protected final String nome;
    private final String identificador;
    private final Connection connection;
    private final Channel channel;
    private final KeyStoreManager keys;
    private final Object lockPublicacao = new Object();
    private final Map<String, Registro<?>> tratadores = new LinkedHashMap<>();

    protected ProcessoMensageria(String nome, String identificador, boolean publicaEventos) throws Exception {
        this.nome = nome;
        this.identificador = identificador;
        this.connection = RabbitConfig.newConnection();
        this.channel = connection.createChannel();
        this.keys = new KeyStoreManager(identificador, publicaEventos);
    }

    public abstract void iniciar() throws Exception;

    protected void declararExchange(String exchange, BuiltinExchangeType tipo) throws IOException {
        channel.exchangeDeclare(exchange, tipo, true);
    }

    protected void declararFila(String exchange, String fila, String... bindingKeys) throws IOException {
        channel.queueDeclare(fila, true, false, false, null);
        for (String bindingKey : bindingKeys) {
            channel.queueBind(fila, exchange, bindingKey);
        }
    }

    protected <T> void registrarTratador(String bindingKey, Class<T> tipoPayload, TratadorEvento<T> tratador) {
        tratadores.put(bindingKey, new Registro<>(tipoPayload, tratador));
    }

    protected void publicar(String exchange, String routingKey, Object payload) throws IOException {
        synchronized (lockPublicacao) {
            EventBus.publicar(channel, exchange, routingKey, identificador, payload, keys.getChavePrivadaPropria());
        }
    }

    protected void consumir(String fila) throws IOException {
        Channel canalConsumo = connection.createChannel();
        canalConsumo.basicQos(1);
        DeliverCallback callback = (consumerTag, delivery) -> {
            try {
                processarEntrega(delivery);
            } catch (Exception e) {
                System.err.println("[" + nome + "] Erro ao processar evento: " + e + ". Evento descartado.");
            } finally {
                canalConsumo.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            }
        };
        canalConsumo.basicConsume(fila, false, callback, consumerTag -> {});
    }

    protected void log(String mensagem) {
        System.out.println("[" + nome + "] " + mensagem);
    }

    private void processarEntrega(Delivery delivery) throws Exception {
        String routingKey = delivery.getEnvelope().getRoutingKey();
        Registro<?> registro = buscarTratador(routingKey);
        if (registro == null) {
            log("Nenhum tratador para o evento '" + routingKey + "'. Evento descartado.");
            return;
        }
        registro.processar(delivery);
    }

    private Registro<?> buscarTratador(String routingKey) {
        for (Map.Entry<String, Registro<?>> entrada : tratadores.entrySet()) {
            if (combina(entrada.getKey(), routingKey)) {
                return entrada.getValue();
            }
        }
        return null;
    }

    // '*' vale exatamente uma palavra, igual às binding keys de exchanges topic do RabbitMQ
    private static boolean combina(String bindingKey, String routingKey) {
        String[] padrao = bindingKey.split("\\.");
        String[] palavras = routingKey.split("\\.");
        if (padrao.length != palavras.length) {
            return false;
        }
        for (int i = 0; i < padrao.length; i++) {
            if (!padrao[i].equals("*") && !padrao[i].equals(palavras[i])) {
                return false;
            }
        }
        return true;
    }

    private class Registro<T> {
        private final Class<T> tipoPayload;
        private final TratadorEvento<T> tratador;

        Registro(Class<T> tipoPayload, TratadorEvento<T> tratador) {
            this.tipoPayload = tipoPayload;
            this.tratador = tratador;
        }

        void processar(Delivery delivery) throws Exception {
            EventBus.EventoRecebido<T> evento = EventBus.receber(delivery, keys, tipoPayload);
            if (!evento.valido) {
                log("Evento '" + delivery.getEnvelope().getRoutingKey() + "' descartado: " + evento.motivo + ".");
                return;
            }
            tratador.tratar(evento.payload);
        }
    }
}
