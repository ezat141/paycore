package com.paycore.payment.support;

import com.paycore.payment.payment.PaymentRepository;
import com.paycore.payment.payment.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

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
@Import({PostgresContainerConfiguration.class, RedisContainerConfiguration.class})
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

    @Autowired
    protected StringRedisTemplate redis;

    /**
     * A spy on the shared bean, not a separate mock context: every subclass reuses one Spring
     * context. {@code @MockitoSpyBean} defaults to {@code MockReset.AFTER}, so Mockito resets its
     * stubbing (but not the underlying repository) after every test method.
     */
    @MockitoSpyBean
    protected PaymentRepository paymentRepository;

    @BeforeEach
    void resetState() {
        jdbc.sql("TRUNCATE idempotency_keys, payments").update();
        acquirer.reset();
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    protected ResultActions create(RequestPostProcessor token, String idempotencyKey, String body) throws Exception {
        return mockMvc.perform(Requests.createPayment(token, idempotencyKey, body));
    }

    protected long paymentCount() {
        return jdbc.sql("SELECT count(*) FROM payments").query(Long.class).single();
    }

    /** Inserts a payment directly, for states and timestamps the API cannot produce on demand. */
    protected UUID seedPayment(String tenant, PaymentStatus status, Instant createdAt,
                               String authorizationId, Instant transitionStartedAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO payments (id, tenant, amount_minor, currency, card_token, status,
                                              acquirer_authorization_id, transition_started_at,
                                              version, created_at, updated_at)
                        VALUES (:id, :tenant, 1999, 'USD', 'tok_approve', :status,
                                :authorizationId, :transitionStartedAt, 0, :createdAt, :createdAt)""")
                .param("id", id)
                .param("tenant", tenant)
                .param("status", status.name())
                .param("authorizationId", authorizationId, Types.VARCHAR)
                .param("transitionStartedAt", utc(transitionStartedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("createdAt", utc(createdAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return id;
    }

    protected UUID seedPayment(String tenant, PaymentStatus status, Instant createdAt) {
        return seedPayment(tenant, status, createdAt, null, null);
    }

    /** An IN_PROGRESS key whose lease ends at {@code lockedUntil} — live if in the future. */
    protected void seedKey(String tenant, String key, String fingerprint, UUID paymentId, Instant lockedUntil) {
        jdbc.sql("""
                        INSERT INTO idempotency_keys (tenant, idempotency_key, fingerprint, payment_id,
                                                      state, locked_until)
                        VALUES (:tenant, :key, :fingerprint, :paymentId, 'IN_PROGRESS', :lockedUntil)""")
                .param("tenant", tenant)
                .param("key", key)
                .param("fingerprint", fingerprint)
                .param("paymentId", paymentId)
                .param("lockedUntil", utc(lockedUntil), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    protected static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
