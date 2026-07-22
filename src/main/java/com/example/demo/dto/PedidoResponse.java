package com.example.demo.dto;

public record PedidoResponse(String id, String status, boolean degraded) {

    /** Valor default retornado quando o circuito abre ou há falha técnica. */
    public static PedidoResponse defaultValue(String id) {
        return new PedidoResponse(id, "INDISPONIVEL", true);
    }
}
