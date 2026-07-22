package com.example.demo.web;

import com.example.demo.service.BulkheadedPedidoService;
import com.example.demo.dto.PedidoResponse;
import com.example.demo.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PedidoController {

    private final BulkheadedPedidoService service;

    public PedidoController(BulkheadedPedidoService service) {
        this.service = service;
    }

    @GetMapping("/api/pedidos/{id}")
    public ResponseEntity<PedidoResponse> buscar(@PathVariable String id) {
        PedidoResponse pedido = service.buscarPedido(id);
        // Sinaliza degradação para o consumidor via header
        return ResponseEntity.ok()
                .header("X-Degraded", String.valueOf(pedido.degraded()))
                .body(pedido);
    }

    @ExceptionHandler(BusinessException.class)
    public ProblemDetail handleBusiness(BusinessException ex) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.valueOf(ex.getStatus()), ex.getMessage());
    }
}
