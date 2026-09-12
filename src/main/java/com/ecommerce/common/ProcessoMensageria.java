package com.ecommerce.common;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Classe base de todos os processos do sistema (os 5 microsserviços e os
 * consumidores de promoções). Concentra o que é igual em todos eles:
 *  - conexão com o RabbitMQ e carregamento das chaves;
 *  - declaração de exchanges e filas;
 *  - publicação de eventos assinados;
 *  - consumo de uma fila: cada evento é validado (EventBus.receber), os
 *    inválidos são descartados e os válidos vão para o TratadorEvento
 *    registrado para a routing key.
 *
 * Cada subclasse implementa iniciar(), onde declara sua topologia e registra
 * os tratadores dos eventos que consome.
 */
public abstract class ProcessoMensageria {

    protected final String nome;          // nome exibido nos logs, ex.: "Estoque"
    private final String identificador;   // nome usado nas chaves e como producer, ex.: "estoque"
    private final Connection connection;
    private final Channel channel;
    private final KeyStoreManager keys;
    private final Object lockPublicacao = new Object();
    private final Map<String, Registro<?>> tratadores = new LinkedHashMap<>();

    /**
     * @param nome           nome exibido nos logs
     * @param identificador  pasta de chaves em keys/ e nome de produtor nos eventos
     * @param publicaEventos true se o processo publica eventos (carrega a chave privada)
     */
    protected ProcessoMensageria(String nome, String identificador, boolean publicaEventos) throws Exception {
        this.nome = nome;
        this.identificador = identificador;
        this.connection = RabbitConfig.newConnection();
        this.channel = connection.createChannel();
        this.keys = new KeyStoreManager(identificador, publicaEventos);
    }

    /** Declara a topologia, registra os tratadores e começa a trabalhar. */
    public abstract void iniciar() throws Exception;

    protected void declararExchange(String exchange, BuiltinExchangeType tipo) throws IOException {
        channel.exchangeDeclare(exchange, tipo, true);
    }

    /** Declara uma fila durável e a associa à exchange com cada binding key. */
    protected void declararFila(String exchange, String fila, String... bindingKeys) throws IOException {
        channel.queueDeclare(fila, true, false, false, null);
        for (String bindingKey : bindingKeys) {
            channel.queueBind(fila, exchange, bindingKey);
        }
    }

    /**
     * Registra o tratador de um evento. A binding key pode ser uma routing key
     * exata ("pedido.criado") ou um padrão com '*' ("promocao.categoria.*").
     */
    protected <T> void registrarTratador(String bindingKey, Class<T> tipoPayload, TratadorEvento<T> tratador) {
        tratadores.put(bindingKey, new Registro<>(tipoPayload, tratador));
    }

    /** Publica um evento assinado com a chave privada deste processo. */
    protected void publicar(String exchange, String routingKey, Object payload) throws IOException {
        synchronized (lockPublicacao) {
            EventBus.publicar(channel, exchange, routingKey, identificador, payload, keys.getChavePrivadaPropria());
        }
    }

    /** Começa a consumir a fila, um evento por vez, confirmando (ack) cada um após o processamento. */
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

    /** Mesma regra do RabbitMQ para binding keys de exchanges topic: '*' vale exatamente uma palavra. */
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

    /** Associa o tipo do payload ao tratador, para desserializar o evento com a classe certa. */
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
