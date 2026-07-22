package com.example.demo.fallback;

import com.example.demo.client.PedidoClient;
import com.example.demo.dto.PedidoResponse;
import com.example.demo.exception.BusinessException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
public class PedidoClientFallbackFactory implements FallbackFactory<PedidoClient> {

    private static final Logger log =
            LoggerFactory.getLogger(PedidoClientFallbackFactory.class);

    @Override
    public PedidoClient create(Throwable cause) {
        return id -> {
            // Erro de negócio: propaga intacto. O fallback não deve
            // transformar um 404/422 em resposta default silenciosa.
            if (cause instanceof BusinessException be) {
                throw be;
            }

            if (cause instanceof CallNotPermittedException) {
                // Circuito ABERTO: não logar por chamada a 2k req/s —
                // as métricas do Resilience4j já contam isso.
                return PedidoResponse.defaultValue(id);
            }

            // Falha técnica (timeout, conexão, 5xx, bulkhead cheio)
            log.warn("Fallback tecnico para pedido {}: {}", id, cause.toString());
            return PedidoResponse.defaultValue(id);
        };
    }
}
