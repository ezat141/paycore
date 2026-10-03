package com.paycore.payment.idempotency;

import com.paycore.payment.support.FakeAcquirer;
import com.paycore.payment.support.PostgresContainerConfiguration;
import com.paycore.payment.support.RedisContainerConfiguration;
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
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;

import static com.paycore.payment.support.Requests.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A Redis that accepts connections and never answers: the case the 200 ms timeouts exist for.
 * RedisDownTest's closed port is refused at once and never reaches them.
 *
 * <p>Its own context, with the production 200 ms timeouts, because those are what it tests: the
 * shared base raises them to 2 s against a cold-start flake, and a bound loose enough for 2 s
 * would no longer prove 200 ms. The container is this context's own, so pausing it can't disturb
 * another test class, and it is unpaused in a {@code finally} whatever the test does.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:9999/jwks",
        "paycore.acquirer.read-timeout=1s",
        "paycore.outbox.relay-enabled=false",
        "spring.kafka.admin.auto-create=false"})
@AutoConfigureMockMvc
@Import({PostgresContainerConfiguration.class, RedisContainerConfiguration.class})
class RedisPausedTest {

    /** Three Redis calls at 200 ms each, plus the request itself, with room for a slow machine. */
    private static final Duration BOUND = Duration.ofSeconds(3);

    private static final FakeAcquirer acquirer = FakeAcquirer.start();

    @DynamicPropertySource
    static void acquirerLocation(DynamicPropertyRegistry registry) {
        registry.add("paycore.acquirer.base-url", () -> acquirer.baseUrl().toString());
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    GenericContainer<?> redisContainer;

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
    void aPausedRedisCostsAtMostItsTimeoutsAndPaymentsAreStillCreated() throws Exception {
        // Warm up the connection first, so what the pause stalls is commands, not the handshake.
        mockMvc.perform(Requests.createPayment(Tokens.writer("acme"), "warm-up", body(1999, "tok_approve")))
                .andExpect(status().isCreated());

        String id = redisContainer.getContainerId();
        DockerClientFactory.instance().client().pauseContainerCmd(id).exec();
        try {
            long started = System.nanoTime();
            mockMvc.perform(Requests.createPayment(Tokens.writer("acme"), "order-1", body(1999, "tok_approve")))
                    .andExpect(status().isCreated());
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(BOUND);

            mockMvc.perform(Requests.createPayment(Tokens.writer("acme"), "order-1", body(1999, "tok_approve")))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Idempotent-Replayed", "true"));
        } finally {
            DockerClientFactory.instance().client().unpauseContainerCmd(id).exec();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM payments WHERE tenant = 'acme'").query(Long.class).single())
                .isEqualTo(2);
        assertThat(acquirer.totalAuthorizeCalls()).isEqualTo(2);
    }
}
