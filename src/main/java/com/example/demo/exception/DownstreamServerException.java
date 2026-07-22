package com.example.demo.exception;

/**
 * Erros técnicos do serviço downstream (5xx).
 * Contam como falha para o circuit breaker (recordExceptions).
 */
public class DownstreamServerException extends RuntimeException {
    public DownstreamServerException(String message) {
        super(message);
    }
}
