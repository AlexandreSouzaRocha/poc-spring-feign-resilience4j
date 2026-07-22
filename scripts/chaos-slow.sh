#!/usr/bin/env bash
# Injeta latência de 2s (> slowCallDurationThreshold de 500ms).
# O circuito abre por SLOW CALL RATE mesmo com respostas 200 —
# é o cenário mais realista de degradação em produção.
curl -s -X POST http://localhost:8081/__admin/mappings \
  -H 'Content-Type: application/json' \
  -d '{
    "id": "6fd12b32-88b3-4d33-b4fe-4a326fe07f37",
    "priority": 1,
    "request": { "method": "GET", "urlPathPattern": "/pedidos/.*" },
    "response": {
      "status": 200,
      "headers": { "Content-Type": "application/json" },
      "jsonBody": { "id": "x", "status": "CONFIRMADO", "degraded": false },
      "fixedDelayMilliseconds": 2000
    }
  }'
echo "Caos SLOW ativo (2s de latencia)."
