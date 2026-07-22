package com.example.demo.client;

import com.example.demo.dto.PedidoResponse;
import com.example.demo.fallback.PedidoClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(
        name = "pedidoClient",
        url = "${api.pedidos.url}",
        fallbackFactory = PedidoClientFallbackFactory.class
)
public interface PedidoClient {

    @GetMapping("/pedidos/{id}")
    PedidoResponse buscarPedido(@PathVariable("id") String id);
}
