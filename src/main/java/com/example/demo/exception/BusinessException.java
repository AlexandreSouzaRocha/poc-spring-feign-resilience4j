package com.example.demo.exception;

/**
 * Erros de negócio (4xx) traduzidos pelo ErrorDecoder.
 * NÃO contam para o circuit breaker (ignoreExceptions) e
 * NÃO são mascarados pelo fallback.
 */
public class BusinessException extends RuntimeException {
    private final int status;

    public BusinessException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
