package com.example.demo.service;

import com.example.demo.client.PedidoClient;
import com.example.demo.dto.PedidoResponse;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import org.springframework.stereotype.Service;

/**
 * Camada fina que aplica o Bulkhead ANTES do Feign/CircuitBreaker.
 * A 2k req/s isso garante que uma dependência lenta nunca ocupe
 * mais que maxConcurrentCalls conexões/threads simultâneas.
 *
 * Quando o bulkhead está cheio, BulkheadFullException é lançada
 * imediatamente e tratada pelo fallbackMethod (fail fast).
 */
@Service
public class BulkheadedPedidoService {

    private final PedidoClient pedidoClient;

    public BulkheadedPedidoService(PedidoClient pedidoClient) {
        this.pedidoClient = pedidoClient;
    }

    @Bulkhead(name = "pedidoClient", fallbackMethod = "bulkheadFallback")
    public PedidoResponse buscarPedido(String id) {
        return pedidoClient.buscarPedido(id);
    }

    private PedidoResponse bulkheadFallback(String id,
            io.github.resilience4j.bulkhead.BulkheadFullException ex) {
        return PedidoResponse.defaultValue(id);
    }
}
