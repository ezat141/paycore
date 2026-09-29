package com.paycore.payment.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Base for every test that needs the running service. Every subclass carries identical
 * configuration, so Spring caches one context — and one set of containers — for all of them.
 * A test needing a <em>different</em> configuration must not extend this; see RedisDownTest.
 *
 * <p>The acquirer's read timeout is 1s here, not the 2s default, so {@code tok_timeout} tests take a
 * second; the fake stalls for three. Not lower: Spring's read timeout covers the whole exchange,
 * so a cold JVM's first call (HttpClient and Jackson warm-up) must fit inside it.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:9999/jwks",
        "paycore.acquirer.read-timeout=1s"})
@AutoConfigureMockMvc
@Import(PostgresContainerConfiguration.class)
public abstract class PaymentServiceTest {

    protected static final FakeAcquirer acquirer = FakeAcquirer.start();

    @DynamicPropertySource
    static void acquirerLocation(DynamicPropertyRegistry registry) {
        registry.add("paycore.acquirer.base-url", () -> acquirer.baseUrl().toString());
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void resetState() {
        jdbc.sql("TRUNCATE payments").update();
        acquirer.reset();
    }

    protected ResultActions create(RequestPostProcessor token, String idempotencyKey, String body) throws Exception {
        return mockMvc.perform(Requests.createPayment(token, idempotencyKey, body));
    }

    protected long paymentCount() {
        return jdbc.sql("SELECT count(*) FROM payments").query(Long.class).single();
    }
}
