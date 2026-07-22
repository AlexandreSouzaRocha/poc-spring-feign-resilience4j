#!/usr/bin/env bash
# Injeta falha 500 em todas as chamadas — o circuito deve abrir em ~10s de carga
curl -s -X POST http://localhost:8081/__admin/mappings \
  -H 'Content-Type: application/json' \
  -d '{
    "id": "1e2b1b9d-dbca-440d-ad47-b42009b7b375",
    "priority": 1,
    "request": { "method": "GET", "urlPathPattern": "/pedidos/.*" },
    "response": { "status": 500, "body": "boom" }
  }'
echo "Caos 500 ATIVO. Observe: curl -s localhost:8080/actuator/circuitbreakers | jq"
