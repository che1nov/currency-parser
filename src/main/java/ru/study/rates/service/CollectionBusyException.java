package ru.study.rates.service;

public class CollectionBusyException extends RuntimeException {
    public CollectionBusyException() { super("Сбор курсов уже выполняется; повторите запрос позже"); }
}
