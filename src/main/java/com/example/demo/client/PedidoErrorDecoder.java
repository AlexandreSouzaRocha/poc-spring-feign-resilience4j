package com.example.demo.client;

import com.example.demo.exception.BusinessException;
import com.example.demo.exception.DownstreamServerException;
import feign.Response;
import feign.codec.ErrorDecoder;

/**
 * Traduz status HTTP em exceções tipadas.
 * Só existem dois desfechos: 4xx é erro de NEGÓCIO (ignorado pelo
 * circuit breaker) e QUALQUER outro status não-2xx é falha TÉCNICA
 * (conta para o circuito via recordExceptions). Não há caminho default:
 * 1xx/3xx não seguidos pelo client também caem em DownstreamServerException.
 */
public class PedidoErrorDecoder implements ErrorDecoder {

    @Override
    public Exception decode(String methodKey, Response response) {
        int status = response.status();

        if (status >= 400 && status < 500) {
            return new BusinessException(status,
                    "Erro de negócio em " + methodKey + ": HTTP " + status);
        }
        // Tudo que não for 4xx é técnico: 5xx, além de 1xx/3xx que o
        // client não conseguiu resolver (redirect sem Location, 304, etc.).
        return new DownstreamServerException(
                "Falha no serviço de pedidos em " + methodKey + ": HTTP " + status);
    }
}
