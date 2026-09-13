package com.ecommerce.common;

/**
 * Ação executada quando um processo recebe um evento válido (com a assinatura
 * já verificada). Cada processo registra um tratador por routing key em
 * ProcessoMensageria.registrarTratador(...).
 *
 * @param <T> tipo do payload do evento (uma das classes de Payloads)
 */
@FunctionalInterface
public interface TratadorEvento<T> {

    void tratar(T payload) throws Exception;
}
