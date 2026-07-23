# feign-cb-demo — Circuit Breaker (Feign + Resilience4j)

Spring Boot 3.5.3 / Java 25 / Spring Cloud 2025.0.0

## Subir o ambiente

    docker compose up --build -d app wiremock

- App:      http://localhost:8080
- WireMock: http://localhost:8081/__admin

Sanidade:

    curl -s localhost:8080/api/pedidos/123 | jq
    # {"id":"123","status":"CONFIRMADO","degraded":false}

## Fluxo de disparo do circuit breaker

Toda consulta atravessa quatro camadas antes de bater no serviço de pedidos:

    Controller
      -> BulkheadedPedidoService   (@Bulkhead: teto de chamadas concorrentes)
        -> PedidoClient            (@FeignClient: CircuitBreaker + fallbackFactory)
          -> serviço de pedidos    (HTTP downstream)

### 1. Classificacao da falha (ErrorDecoder)

`PedidoErrorDecoder` traduz o status HTTP em exceção tipada, e isso decide se
a chamada conta ou nao para o circuito:

| Status downstream | Exceção                    | Efeito no circuito         |
|-------------------|----------------------------|----------------------------|
| 4xx               | `BusinessException`        | IGNORADA (`ignoreExceptions`) |
| 5xx / 1xx / 3xx   | `DownstreamServerException`| CONTA (`recordExceptions`) |
| timeout / conexão | `RetryableException`, `IOException`, `TimeoutException` | CONTA (`recordExceptions`) |

Chamada LENTA (acima de `slowCallDurationThreshold`, 500ms) tambem CONTA, mesmo
retornando 200 — sob alta carga, dependência lenta é pior que dependência fora.

### 2. Quando o circuito abre

O CircuitBreaker acumula resultados numa janela TIME_BASED de 10s e só decide
apos `minimumNumberOfCalls`. Abre (CLOSED -> OPEN) quando, na janela:

- `failureRate > 50%`  (falhas técnicas registradas), OU
- `slowCallRate > 80%` (chamadas acima de 500ms)

### 3. Estados e transicoes

| Estado    | Comportamento                                                        |
|-----------|----------------------------------------------------------------------|
| CLOSED    | chamadas passam; resultados sao medidos                              |
| OPEN      | fail-fast: lança `CallNotPermittedException` sem tocar no downstream |
| HALF_OPEN | apos `waitDurationInOpenState` (5s); libera N chamadas de sondagem   |

De HALF_OPEN: sondagens OK -> CLOSED; sondagens falhando -> OPEN de novo.

### 4. O fallback intercepta a exceção (importante)

Como o `@FeignClient` tem `fallbackFactory`, **a chamada do Feign nunca propaga
falha técnica**: em vez de lançar, ela RETORNA o valor default degradado
(`status=INDISPONIVEL`, `degraded=true`, header `X-Degraded: true`).

    RetryableException / 5xx / timeout / CallNotPermitted (circuito aberto)
        -> fallbackFactory -> retorna PedidoResponse.defaultValue(id)   (NÃO lança)

    BusinessException (4xx)
        -> fallbackFactory faz `throw be` -> propaga -> Controller responde 4xx

Consequência prática: um `try/catch` no service cobrindo `RetryableException`
(ou qualquer falha técnica) seria código morto — o fallback já a converteu em
resposta default antes de ela subir. A única exceção que chega ao service/controller
é a `BusinessException`, porque o fallback a relança de propósito.

O gráfico "Chamadas por resultado" do Grafana reflete isso: `ignored` =
BusinessException; `not_permitted` = bloqueadas com circuito aberto.

## Roteiro: ver o circuito abrir

Terminal 1 — carga continua:

    docker compose --profile load run --rm k6 run /scripts/load.js

Terminal 2 — observar o estado do circuito (a cada 1s):

    watch -n1 'curl -s localhost:8080/actuator/circuitbreakers | jq ".circuitBreakers.pedidoClient | {state, failureRate, slowCallRate, bufferedCalls}"'

Terminal 3 — injetar falha:

    ./scripts/chaos-500.sh     # 100% de 500 -> circuito abre por failureRate
    # ou
    ./scripts/chaos-slow.sh    # 200 OK com 2s -> circuito abre por slowCallRate

O que esperar (com a config atual):

1. CLOSED  -> falhas acumulando na janela de 10s (precisa de >=200 chamadas: por isso a carga do k6)
2. OPEN    -> failureRate > 50% (ou slowCallRate > 80%). No k6, o counter
              `degraded_responses` dispara e o p95 DESPENCA (fallback fail-fast)
3. Apos 5s -> HALF_OPEN automaticamente (50 chamadas de teste)
4. `./scripts/chaos-off.sh` -> chamadas de teste passam -> CLOSED

## Validar que erro de negocio NAO abre o circuito

    curl -s -X POST http://localhost:8081/__admin/mappings \
      -H 'Content-Type: application/json' \
      -d '{"id":"chaos-404","priority":1,
           "request":{"method":"GET","urlPathPattern":"/pedidos/.*"},
           "response":{"status":404}}'

Rode a carga: o app responde 404 (ProblemDetail), o counter `business_4xx`
sobe no k6, e o estado permanece CLOSED com failureRate=-1 ou baixo —
BusinessException esta em `ignoreExceptions`.

Limpar: `curl -X DELETE localhost:8081/__admin/mappings/chaos-404`

## Metricas

    curl -s localhost:8080/actuator/prometheus | grep resilience4j_circuitbreaker

Uteis: `resilience4j_circuitbreaker_state`, `_calls_total`,
`_slow_calls`, `resilience4j_bulkhead_available_concurrent_calls`.

## Observabilidade (Prometheus + Grafana)

    docker compose up -d prometheus grafana

- Grafana:    http://localhost:3000  (login anonimo, dashboard ja carregado como home)
- Prometheus: http://localhost:9090

O dashboard "Circuit Breaker — pedidoClient" mostra:

1. Estado atual (CLOSED/OPEN/HALF_OPEN) e transicoes ao longo do tempo
2. Failure rate vs slow call rate contra os limiares (50% / 80%)
3. Chamadas por resultado (successful/failed/ignored/not_permitted) —
   `ignored` = BusinessException; `not_permitted` = bloqueadas com circuito aberto
4. p95/p99 da API: repare no colapso da latencia quando o circuito abre (fail-fast)
5. Bulkhead: chamadas concorrentes disponiveis — despenca no cenario chaos-slow

Roteiro visual completo:

    docker compose up --build -d
    docker compose --profile load run --rm k6 run /scripts/load.js   # terminal 1
    ./scripts/chaos-slow.sh                                          # terminal 2
    # observe no Grafana: slow_call_rate subindo -> OPEN -> p95 caindo
    ./scripts/chaos-off.sh
