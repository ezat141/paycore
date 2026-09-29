package com.paycore.payment.idempotency;

import com.paycore.payment.payment.PaymentRequest;
import com.paycore.payment.support.PaymentServiceTest;
import com.paycore.payment.support.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;

import static com.paycore.payment.support.Requests.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IdempotencyCacheTest extends PaymentServiceTest {

    private static final String BODY = body(1999, "tok_approve");
    private static final String BODY_FINGERPRINT =
            Fingerprint.of(new PaymentRequest(1999L, "USD", "tok_approve", null));

    @Autowired
    private IdempotencyCache cache;

    @Test
    void aCompletedRequestIsCachedWithAnExpiry() throws Exception {
        create(Tokens.writer("acme"), "order-1", BODY).andExpect(status().isCreated());

        String key = IdempotencyCache.redisKey("acme", "order-1");
        assertThat(redis.opsForValue().get(key)).contains("\"state\":\"COMPLETED\"");
        assertThat(redis.getExpire(key)).isPositive();
    }

    @Test
    void anUnknownOutcomeLeavesNothingInRedis() throws Exception {
        create(Tokens.writer("acme"), "order-1", body(1999, "tok_timeout")).andExpect(status().isBadGateway());

        assertThat(redis.opsForValue().get(IdempotencyCache.redisKey("acme", "order-1"))).isNull();
    }

    /** Redis forgot; Postgres did not. The replay from Postgres puts the answer back in Redis (spec §7.4). */
    @Test
    void aReplayStillWorksAfterRedisIsFlushed() throws Exception {
        create(Tokens.writer("acme"), "order-1", BODY).andExpect(status().isCreated());
        redis.execute((RedisCallback<Void>) connection -> flush(connection));

        create(Tokens.writer("acme"), "order-1", BODY)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(acquirer.totalAuthorizeCalls()).isEqualTo(1);
        assertThat(redis.opsForValue().get(IdempotencyCache.redisKey("acme", "order-1")))
                .contains("\"state\":\"COMPLETED\"");
    }

    /**
     * A mismatch that only Postgres can see (Redis was flushed) takes the Redis lock with the
     * wrong fingerprint before Postgres refuses it. Unless that lock is released, the genuine
     * retry would be told 422 by Redis until the lock expired.
     */
    @Test
    void aMismatchRefusedByPostgresLeavesNoLockInRedis() throws Exception {
        create(Tokens.writer("acme"), "order-1", BODY).andExpect(status().isCreated());
        redis.execute((RedisCallback<Void>) connection -> flush(connection));

        create(Tokens.writer("acme"), "order-1", body(2500, "tok_approve")).andExpect(status().is(422));

        create(Tokens.writer("acme"), "order-1", BODY)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    /** Spec §7.3: a request whose lock expired must never delete the lock another request now holds. */
    @Test
    void onlyTheOwnerCanReleaseALock() {
        assertThat(cache.tryLock("acme", "order-1", BODY_FINGERPRINT, "owner-a"))
                .isEqualTo(IdempotencyCache.LockResult.ACQUIRED);

        cache.release("acme", "order-1", "owner-b");
        assertThat(redis.hasKey(IdempotencyCache.redisKey("acme", "order-1"))).isTrue();
        assertThat(cache.tryLock("acme", "order-1", BODY_FINGERPRINT, "owner-b"))
                .isEqualTo(IdempotencyCache.LockResult.HELD);

        cache.release("acme", "order-1", "owner-a");
        assertThat(redis.hasKey(IdempotencyCache.redisKey("acme", "order-1"))).isFalse();
    }

    @Test
    void aKeyHeldInRedisIsA409WithoutTouchingTheAcquirer() throws Exception {
        holdInRedis("acme", "order-1", BODY_FINGERPRINT);

        create(Tokens.writer("acme"), "order-1", BODY)
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"));
        assertThat(paymentCount()).isZero();
        assertThat(acquirer.totalAuthorizeCalls()).isZero();
    }

    @Test
    void aKeyHeldInRedisForADifferentRequestIsA422() throws Exception {
        holdInRedis("acme", "order-1", Fingerprint.of(new PaymentRequest(2500L, "USD", "tok_approve", null)));

        create(Tokens.writer("acme"), "order-1", BODY).andExpect(status().is(422));
    }

    /** Without the length prefix these two would share a Redis key across tenants. */
    @Test
    void tenantAndKeyCannotCollideInTheRedisKey() {
        assertThat(IdempotencyCache.redisKey("a:b", "c")).isNotEqualTo(IdempotencyCache.redisKey("a", "b:c"));
    }

    private void holdInRedis(String tenant, String key, String fingerprint) {
        redis.opsForValue().set(IdempotencyCache.redisKey(tenant, key), """
                {"state":"IN_PROGRESS","fingerprint":"%s","owner":"another-request","responseStatus":null,"responseBody":null}"""
                .formatted(fingerprint));
    }

    private static Void flush(RedisConnection connection) {
        connection.serverCommands().flushAll();
        return null;
    }
}
