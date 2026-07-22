package com.example.demo.config;

import com.example.demo.client.PedidoErrorDecoder;
import feign.Logger;
import feign.codec.ErrorDecoder;
import org.springframework.cloud.openfeign.CircuitBreakerNameResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignResilienceConfig {

    /**
     * Usa o name do @FeignClient como nome do circuito
     * (em vez do id alfanumérico client+método).
     * Todos os métodos do client compartilham UM circuito.
     */
    @Bean
    public CircuitBreakerNameResolver circuitBreakerNameResolver() {
        return (feignClientName, target, method) -> feignClientName;
    }

    @Bean
    public ErrorDecoder errorDecoder() {
        return new PedidoErrorDecoder();
    }

    /**
     * Logger customizado que emite request/response do Feign em INFO.
     * Só efetiva o log se o loggerLevel do client for >= BASIC (application.yml).
     */
    @Bean
    public Logger feignLogger() {
        return new FeignInfoLogger();
    }
}
