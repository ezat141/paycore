package com.paycore.payment.idempotency;

import com.paycore.payment.support.FakeAcquirer;
import com.paycore.payment.support.PostgresContainerConfiguration;
import com.paycore.payment.support.Requests;
import com.paycore.payment.support.Tokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.paycore.payment.support.Requests.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Redis pointed at a port nothing listens on. Deliberately does not extend PaymentServiceTest:
 * it needs a context <em>without</em> a Redis container. The whole spec §7 guarantee must
 * still hold, because Postgres, not Redis, enforces it.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:9999/jwks",
        "paycore.acquirer.read-timeout=1s",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1"})
@AutoConfigureMockMvc
@Import(PostgresContainerConfiguration.class)
class RedisDownTest {

    private static final FakeAcquirer acquirer = FakeAcquirer.start();

    @DynamicPropertySource
    static void acquirerLocation(DynamicPropertyRegistry registry) {
        registry.add("paycore.acquirer.base-url", () -> acquirer.baseUrl().toString());
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @AfterAll
    static void stop() {
        acquirer.close();
    }

    @BeforeEach
    void resetState() {
        jdbc.sql("TRUNCATE idempotency_keys, payments").update();
        acquirer.reset();
    }

    @Test
    void paymentsAreStillCreatedAndReplayed() throws Exception {
        mockMvc.perform(Requests.createPayment(Tokens.writer("acme"), "order-1", body(1999, "tok_approve")))
                .andExpect(status().isCreated());
        mockMvc.perform(Requests.createPayment(Tokens.writer("acme"), "order-1", body(1999, "tok_approve")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        assertThat(paymentCount()).isEqualTo(1);
    }

    @Test
    void concurrentRetriesStillProduceOnePaymentAndOneCharge() throws Exception {
        acquirer.authorizeDelay(Duration.ofMillis(300));
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return mockMvc.perform(Requests.createPayment(Tokens.writer("acme"), "order-burst",
                            body(1999, "tok_approve"))).andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(statuses).allMatch(status -> status == 201 || status == 409).contains(201);
            assertThat(paymentCount()).isEqualTo(1);
            assertThat(acquirer.totalAuthorizeCalls()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void healthStaysUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private long paymentCount() {
        return jdbc.sql("SELECT count(*) FROM payments").query(Long.class).single();
    }
}
