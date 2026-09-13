package com.ecommerce.common;

@FunctionalInterface
public interface TratadorEvento<T> {

    void tratar(T payload) throws Exception;
}
