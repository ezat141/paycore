package com.paycore.payment.idempotency;

import com.paycore.payment.payment.PaymentRequest;
import com.paycore.payment.support.PaymentServiceTest;
import com.paycore.payment.support.Tokens;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;

import static com.paycore.payment.support.Requests.body;
import static com.paycore.payment.support.Requests.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IdempotencyCacheTest extends PaymentServiceTest {

    private static final String BODY = body(1999, "tok_approve");
    private static final String BODY_FINGERPRINT =
            Fingerprint.of(new PaymentRequest(1999L, "USD", "tok_approve", null));
    private static final String OTHER_FINGERPRINT =
            Fingerprint.of(new PaymentRequest(2500L, "USD", "tok_approve", null));
    private static final String KEY = IdempotencyCache.redisKey("acme", "order-1");

    @Autowired
    private IdempotencyCache cache;

    /** Kept for response-ttl (24 h), not lock-ttl (30 s): the expiry is well above an hour. */
    @Test
    void aCompletedRequestIsCachedWithAnExpiry() throws Exception {
        create(Tokens.writer("acme"), "order-1", BODY).andExpect(status().isCreated());

        assertThat(redis.opsForValue().get(KEY)).contains("\"state\":\"COMPLETED\"");
        assertThat(redis.getExpire(KEY)).isGreaterThan(Duration.ofHours(23).toSeconds());
    }

    @Test
    void anUnknownOutcomeLeavesNothingInRedis() throws Exception {
        create(Tokens.writer("acme"), "order-1", body(1999, "tok_timeout")).andExpect(status().isBadGateway());

        assertThat(redis.opsForValue().get(KEY)).isNull();
    }

    /** A replay Redis can answer never reaches Postgres. */
    @Test
    void aReplayFromRedisDoesNotTouchPostgres() throws Exception {
        create(Tokens.writer("acme"), "order-1", BODY).andExpect(status().isCreated());
        Mockito.clearInvocations(paymentWrites);

        create(Tokens.writer("acme"), "order-1", BODY)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        verify(paymentWrites, never()).start(any(), any(), any(), any(), any());
    }

    /** Redis forgot; Postgres did not. The replay from Postgres puts the answer back in Redis (spec §7.4). */
    @Test
    void aReplayStillWorksAfterRedisIsFlushed() throws Exception {
        create(Tokens.writer("acme"), "order-1", BODY).andExpect(status().isCreated());
        flushRedis();

        create(Tokens.writer("acme"), "order-1", BODY)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(acquirer.totalAuthorizeCalls()).isEqualTo(1);
        assertThat(redis.opsForValue().get(KEY)).contains("\"state\":\"COMPLETED\"");
    }

    /**
     * A mismatch that only Postgres can see (Redis was flushed) takes the Redis lock with the
     * wrong fingerprint before Postgres refuses it. Unless that lock is released, the genuine
     * retry would be told 409 by Redis until the lock expired.
     */
    @Test
    void aMismatchRefusedByPostgresLeavesNoLockInRedis() throws Exception {
        create(Tokens.writer("acme"), "order-1", BODY).andExpect(status().isCreated());
        flushRedis();

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
        assertThat(redis.hasKey(KEY)).isTrue();
        assertThat(cache.tryLock("acme", "order-1", BODY_FINGERPRINT, "owner-b"))
                .isEqualTo(IdempotencyCache.LockResult.HELD);

        cache.release("acme", "order-1", "owner-a");
        assertThat(redis.hasKey(KEY)).isFalse();
    }

    @Test
    void aKeyHeldInRedisIsA409WithoutTouchingTheAcquirer() throws Exception {
        holdInRedis(BODY_FINGERPRINT);

        create(Tokens.writer("acme"), "order-1", BODY)
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"));
        assertThat(paymentCount()).isZero();
        assertThat(acquirer.totalAuthorizeCalls()).isZero();
    }

    /** An in-flight attempt's fingerprint was never checked against Postgres, so it is not grounds for 422. */
    @Test
    void aKeyHeldInRedisForADifferentRequestIsA409() throws Exception {
        holdInRedis(OTHER_FINGERPRINT);

        create(Tokens.writer("acme"), "order-1", BODY)
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"));
    }

    /** A completed entry was checked against Postgres, so Redis alone may refuse a mismatch. */
    @Test
    void aKeyCompletedInRedisForADifferentRequestIsA422WithoutTouchingPostgres() throws Exception {
        create(Tokens.writer("acme"), "order-1", BODY).andExpect(status().isCreated());
        Mockito.clearInvocations(paymentWrites);

        create(Tokens.writer("acme"), "order-1", body(2500, "tok_approve")).andExpect(status().is(422));
        verify(paymentWrites, never()).start(any(), any(), any(), any(), any());
    }

    /**
     * The review's probe: Postgres completed the key for this request, Redis lost it, and a lock
     * from a mismatched attempt is in Redis now. That lock must not produce a 422 Postgres would never
     * give. Deterministic in two steps: while the lock is held the answer is 409 (retry), and once it
     * is gone (released, or expired) the same request replays.
     */
    @Test
    void aMismatchedInFlightLockNeverRefusesAKeyPostgresWouldReplay() throws Exception {
        create(Tokens.writer("acme"), "order-1", BODY).andExpect(status().isCreated());
        flushRedis();
        holdInRedis(OTHER_FINGERPRINT);

        create(Tokens.writer("acme"), "order-1", BODY)
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"));

        redis.delete(KEY);
        create(Tokens.writer("acme"), "order-1", BODY)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    /**
     * Garbage in Redis is dropped and the request continues on Postgres, which repopulates it.
     * Dropping it matters: left in place, every lock attempt would find the key taken and answer 409.
     */
    @Test
    void anUnreadableRedisEntryIsIgnoredAndReplacedFromPostgres() throws Exception {
        create(Tokens.writer("acme"), "order-1", BODY).andExpect(status().isCreated());
        redis.opsForValue().set(KEY, "{not json");

        create(Tokens.writer("acme"), "order-1", BODY)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        assertThat(redis.opsForValue().get(KEY)).contains("\"state\":\"COMPLETED\"");
        assertThat(cache.get("acme", "order-1")).get().extracting(IdempotencyCache.Entry::completed).isEqualTo(true);
        assertThat(acquirer.totalAuthorizeCalls()).isEqualTo(1);
    }

    /** A lock with no fingerprint cannot be judged; it is dropped rather than answered 409 (or a 500). */
    @Test
    void aRedisEntryWithoutAFingerprintIsDiscarded() throws Exception {
        redis.opsForValue().set(KEY, """
                {"state":"IN_PROGRESS","fingerprint":null,"owner":"another-request"}""");

        create(Tokens.writer("acme"), "order-1", BODY)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        assertThat(paymentCount()).isEqualTo(1);
    }

    /**
     * An entry written by a newer release, during a rolling deploy, still replays from Redis. The
     * spy proves Redis answered: had the entry been discarded, Postgres would have replayed too.
     */
    @Test
    void aRedisEntryWithAnUnknownFieldStillReplays() throws Exception {
        create(Tokens.writer("acme"), "order-1", BODY).andExpect(status().isCreated());
        String newer = redis.opsForValue().get(KEY).replaceFirst("\\{", "{\"addedLater\":1,");
        redis.opsForValue().set(KEY, newer);
        Mockito.clearInvocations(paymentWrites);

        create(Tokens.writer("acme"), "order-1", BODY)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        verify(paymentWrites, never()).start(any(), any(), any(), any(), any());
        assertThat(redis.opsForValue().get(KEY)).isEqualTo(newer);
    }

    /**
     * Postgres has recorded the payment; a fault storing it in Redis (not a Redis outage: any
     * exception) must not turn that success into a 500.
     */
    @Test
    void aCacheFailureAfterTheDatabaseDoesNotTurnSuccessInto500() throws Exception {
        ValueOperations<String, String> values = Mockito.spy(redis.opsForValue());
        doThrow(new IllegalStateException("cache fault")).when(values).set(anyString(), anyString(), any(Duration.class));
        doReturn(values).when(redis).opsForValue();

        create(Tokens.writer("acme"), "order-1", BODY)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
        verify(values).set(anyString(), anyString(), any(Duration.class));
        assertThat(paymentCount()).isEqualTo(1);
    }

    /** A fault releasing the lock must not replace the 502 (and the payment id the client needs) with a 500. */
    @Test
    void aCacheFailureReleasingTheLockKeepsThe502AndItsPaymentId() throws Exception {
        doThrow(new IllegalStateException("cache fault")).when(redis).execute(any(RedisScript.class), anyList(), any());

        String paymentId = read(create(Tokens.writer("acme"), "order-1", body(1999, "tok_timeout"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.paymentId").isNotEmpty()), "$.paymentId");
        assertThat(paymentId).isNotBlank();
        verify(redis).execute(any(RedisScript.class), anyList(), any());
    }

    /** Without the length prefix these two would share a Redis key across tenants. */
    @Test
    void tenantAndKeyCannotCollideInTheRedisKey() {
        assertThat(IdempotencyCache.redisKey("a:b", "c")).isNotEqualTo(IdempotencyCache.redisKey("a", "b:c"));
    }

    private void holdInRedis(String fingerprint) {
        redis.opsForValue().set(KEY, """
                {"state":"IN_PROGRESS","fingerprint":"%s","owner":"another-request","responseStatus":null,"responseBody":null}"""
                .formatted(fingerprint));
    }

    private void flushRedis() {
        redis.execute((RedisCallback<Void>) IdempotencyCacheTest::flush);
    }

    private static Void flush(RedisConnection connection) {
        connection.serverCommands().flushAll();
        return null;
    }
}
