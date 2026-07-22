package com.example.demo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.lessThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
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

    private static WireMockServer downstream;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeAll
    static void startDownstream() {
        downstream = new WireMockServer(options().port(8089));
        downstream.start();
    }

    @AfterAll
    static void stopDownstream() {
        downstream.stop();
    }

    @BeforeEach
    void resetState() {
        downstream.resetAll();
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

        downstream.verify(0, getRequestedFor(urlPathMatching(DOWNSTREAM_PATH)));
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
        downstream.verify(
                lessThanOrExactly(BULKHEAD_MAX_CONCURRENT_CALLS),
                getRequestedFor(urlPathMatching(DOWNSTREAM_PATH)));
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
        downstream.stubFor(getOrder().willReturn(orderPayload()));
    }

    private void givenDownstreamReturnsOrderAfter(int delayMs) {
        downstream.stubFor(getOrder().willReturn(orderPayload().withFixedDelay(delayMs)));
    }

    private void givenDownstreamReturnsStatus(int status) {
        downstream.stubFor(getOrder().willReturn(aResponse().withStatus(status)));
    }

    private static MappingBuilder getOrder() {
        return WireMock.get(urlPathMatching(DOWNSTREAM_PATH));
    }

    private static ResponseDefinitionBuilder orderPayload() {
        return okJson("{\"id\":\"1\",\"status\":\"CONFIRMADO\",\"degraded\":false}");
    }
}
