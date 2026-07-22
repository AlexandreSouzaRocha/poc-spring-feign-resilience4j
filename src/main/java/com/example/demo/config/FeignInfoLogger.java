package com.example.demo.config;

import feign.Logger;
import feign.Request;
import feign.Response;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Logger customizado do Feign que emite request/response em nível INFO.
 *
 * O {@code Logger.Slf4jLogger} padrão do Feign loga em DEBUG. Como o
 * requisito é logar em INFO, sobrescrevemos os hooks e delegamos ao SLF4J
 * no nível desejado. O QUE é logado (linha, headers, body) continua
 * controlado por {@code feign.client.config.<name>.loggerLevel}
 * (NONE / BASIC / HEADERS / FULL) no application.yml.
 *
 * ATENÇÃO: em produção sob alta vazão, FULL loga corpo de todas as
 * chamadas e tem custo relevante — ajuste o loggerLevel por ambiente.
 */
public class FeignInfoLogger extends Logger {

    private final org.slf4j.Logger logger = LoggerFactory.getLogger(FeignInfoLogger.class);

    @Override
    protected void log(String configKey, String format, Object... args) {
        if (logger.isInfoEnabled()) {
            logger.info(String.format(methodTag(configKey) + format, args));
        }
    }

    @Override
    protected void logRequest(String configKey, Level logLevel, Request request) {
        if (logger.isInfoEnabled()) {
            super.logRequest(configKey, logLevel, request);
        }
    }

    @Override
    protected Response logAndRebufferResponse(String configKey, Level logLevel,
            Response response, long elapsedTime) throws IOException {
        if (logger.isInfoEnabled()) {
            return super.logAndRebufferResponse(configKey, logLevel, response, elapsedTime);
        }
        return response;
    }
}
