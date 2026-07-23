package com.example.demo;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.verify.VerificationTimes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PedidoResilienceIT {

    private static final String CIRCUIT_BREAKER = "pedidoClient";
    private static final String DOWNSTREAM_PATH = "/pedidos/.*";
    private static final int BULKHEAD_MAX_CONCURRENT_CALLS = 2;
    private static final int HALF_OPEN_PERMITTED_CALLS = 3;

    private static ClientAndServer downstream;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeAll
    static void startDownstream() {
        System.setProperty("mockserver.logLevel", "WARN");
        downstream = startClientAndServer(8089);
    }

    @AfterAll
    static void stopDownstream() {
        downstream.stop();
    }

    @BeforeEach
    void resetState() {
        downstream.reset();
        circuitBreaker().reset();
    }

    @Test
    @DisplayName("Success: healthy downstream returns the order and keeps the circuit closed")
    void returnsOrderWhenDownstreamSucceeds() throws Exception {
        givenDownstreamReturnsOrder();

        mockMvc.perform(get("/api/pedidos/{id}", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Degraded", "false"))
                .andExpect(jsonPath("$.id").value("1"))
                .andExpect(jsonPath("$.status").value("CONFIRMADO"))
                .andExpect(jsonPath("$.degraded").value(false));

        assertThat(currentState()).isEqualTo(State.CLOSED);
    }

    @Test
    @DisplayName("Closed: business errors (4xx) propagate and never trip the circuit")
    void businessErrorPropagatesAndKeepsCircuitClosed() throws Exception {
        givenDownstreamReturnsStatus(404);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/pedidos/{id}", "p" + i))
                    .andExpect(status().isNotFound());
        }

        assertThat(currentState()).isEqualTo(State.CLOSED);
        assertThat(circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("Closed -> Open: technical failures (5xx) open the circuit and degrade the response")
    void technicalFailuresOpenTheCircuit() throws Exception {
        givenDownstreamReturnsStatus(500);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/pedidos/{id}", "p" + i))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Degraded", "true"))
                    .andExpect(jsonPath("$.status").value("INDISPONIVEL"));
        }

        assertThat(currentState()).isEqualTo(State.OPEN);
    }

    @Test
    @DisplayName("Open: fails fast into the fallback without calling the downstream")
    void openCircuitFailsFastWithoutCallingDownstream() throws Exception {
        givenDownstreamReturnsOrder();
        circuitBreaker().transitionToOpenState();

        mockMvc.perform(get("/api/pedidos/{id}", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Degraded", "true"))
                .andExpect(jsonPath("$.status").value("INDISPONIVEL"));

        downstream.verify(orderRequest(), VerificationTimes.exactly(0));
    }

    @Test
    @DisplayName("Half-open -> Closed: successful probes close the circuit")
    void halfOpenClosesTheCircuitOnSuccess() throws Exception {
        givenDownstreamReturnsOrder();
        circuitBreaker().transitionToOpenState();
        circuitBreaker().transitionToHalfOpenState();
        assertThat(currentState()).isEqualTo(State.HALF_OPEN);

        for (int i = 0; i < HALF_OPEN_PERMITTED_CALLS; i++) {
            mockMvc.perform(get("/api/pedidos/{id}", "p" + i))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Degraded", "false"));
        }

        assertThat(currentState()).isEqualTo(State.CLOSED);
    }

    @Test
    @DisplayName("Half-open -> Open: failed probes reopen the circuit")
    void halfOpenReopensTheCircuitOnFailure() throws Exception {
        givenDownstreamReturnsStatus(500);
        circuitBreaker().transitionToOpenState();
        circuitBreaker().transitionToHalfOpenState();
        assertThat(currentState()).isEqualTo(State.HALF_OPEN);

        for (int i = 0; i < HALF_OPEN_PERMITTED_CALLS; i++) {
            mockMvc.perform(get("/api/pedidos/{id}", "p" + i))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Degraded", "true"));
        }

        assertThat(currentState()).isEqualTo(State.OPEN);
    }

    @Test
    @DisplayName("Bulkhead: concurrent calls beyond the limit fall back without tripping the circuit")
    void bulkheadRejectsExcessConcurrentCalls() throws Exception {
        int totalRequests = 6;
        givenDownstreamReturnsOrderAfter(1000);

        List<String> degradedHeaders = performConcurrently(totalRequests);
        long degradedResponses = degradedHeaders.stream().filter("true"::equals).count();

        assertThat(degradedResponses)
                .isGreaterThanOrEqualTo(totalRequests - BULKHEAD_MAX_CONCURRENT_CALLS);
        downstream.verify(orderRequest(), VerificationTimes.atMost(BULKHEAD_MAX_CONCURRENT_CALLS));
        assertThat(currentState()).isEqualTo(State.CLOSED);
    }

    private List<String> performConcurrently(int totalRequests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(totalRequests);
        CyclicBarrier startLine = new CyclicBarrier(totalRequests);
        List<Future<String>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < totalRequests; i++) {
                String id = "p" + i;
                futures.add(pool.submit(() -> {
                    startLine.await();
                    MvcResult result = mockMvc.perform(get("/api/pedidos/{id}", id)).andReturn();
                    return result.getResponse().getHeader("X-Degraded");
                }));
            }

            List<String> headers = new ArrayList<>();
            for (Future<String> future : futures) {
                headers.add(future.get(10, TimeUnit.SECONDS));
            }
            return headers;
        } finally {
            pool.shutdownNow();
        }
    }

    private CircuitBreaker circuitBreaker() {
        return circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER);
    }

    private State currentState() {
        return circuitBreaker().getState();
    }

    private void givenDownstreamReturnsOrder() {
        downstream.when(orderRequest()).respond(orderResponse());
    }

    private void givenDownstreamReturnsOrderAfter(int delayMs) {
        downstream.when(orderRequest())
                .respond(orderResponse().withDelay(TimeUnit.MILLISECONDS, delayMs));
    }

    private void givenDownstreamReturnsStatus(int status) {
        downstream.when(orderRequest()).respond(response().withStatusCode(status));
    }

    private static HttpRequest orderRequest() {
        return request().withMethod("GET").withPath(DOWNSTREAM_PATH);
    }

    private static HttpResponse orderResponse() {
        return response()
                .withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"1\",\"status\":\"CONFIRMADO\",\"degraded\":false}");
    }
}
