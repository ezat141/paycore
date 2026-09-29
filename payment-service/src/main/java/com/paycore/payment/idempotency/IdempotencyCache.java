package com.paycore.payment.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

/**
 * The Redis fast path for idempotency (spec §7.3). It answers replays and turns away concurrent
 * duplicates without a Postgres round trip.
 *
 * <p><strong>It fails open, and that is safe only because it is not the guard.</strong> Every
 * Redis error is logged and treated as a miss or an acquired lock, and the request continues
 * on the Postgres path, whose primary key still allows one payment per key. If anything ever
 * starts relying on this class for correctness, failing open becomes a double-charge bug.
 */
@Component
public class IdempotencyCache {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCache.class);

    /** Deletes the lock only if we still own it: an expired holder must not delete a newer one. */
    private static final RedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current and string.find(current, ARGV[1], 1, true) then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    public record Entry(String state, String fingerprint, String owner, Integer responseStatus, String responseBody) {

        public boolean completed() {
            return "COMPLETED".equals(state);
        }
    }

    public enum LockResult { ACQUIRED, HELD, UNAVAILABLE }

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final IdempotencyProperties properties;

    public IdempotencyCache(StringRedisTemplate redis, ObjectMapper json, IdempotencyProperties properties) {
        this.redis = redis;
        this.json = json;
        this.properties = properties;
    }

    public Optional<Entry> get(String tenant, String key) {
        try {
            String value = redis.opsForValue().get(redisKey(tenant, key));
            return value == null ? Optional.empty() : Optional.of(json.readValue(value, Entry.class));
        } catch (DataAccessException e) {
            unavailable("read", e);
            return Optional.empty();
        }
    }

    public LockResult tryLock(String tenant, String key, String fingerprint, String owner) {
        try {
            String value = json.writeValueAsString(new Entry("IN_PROGRESS", fingerprint, owner, null, null));
            Boolean set = redis.opsForValue().setIfAbsent(redisKey(tenant, key), value, properties.lockTtl());
            return Boolean.TRUE.equals(set) ? LockResult.ACQUIRED : LockResult.HELD;
        } catch (DataAccessException e) {
            unavailable("lock", e);
            return LockResult.UNAVAILABLE;
        }
    }

    public void complete(String tenant, String key, String fingerprint, int responseStatus, String responseBody) {
        try {
            String value = json.writeValueAsString(new Entry("COMPLETED", fingerprint, null, responseStatus, responseBody));
            redis.opsForValue().set(redisKey(tenant, key), value, properties.responseTtl());
        } catch (DataAccessException e) {
            unavailable("complete", e);
        }
    }

    public void release(String tenant, String key, String owner) {
        try {
            redis.execute(RELEASE_IF_OWNER, List.of(redisKey(tenant, key)), "\"owner\":\"" + owner + "\"");
        } catch (DataAccessException e) {
            unavailable("release", e);
        }
    }

    /**
     * The tenant is length-prefixed, so ("a:b", "c") and ("a", "b:c") stay apart. Without it
     * they would share a Redis key and one tenant could be replayed another's payment.
     */
    static String redisKey(String tenant, String key) {
        return "paycore:idem:" + tenant.length() + ":" + tenant + ":" + key;
    }

    private static void unavailable(String operation, DataAccessException e) {
        log.warn("redis unavailable for idempotency {}; continuing on postgres alone: {}", operation, e.getMessage());
    }
}
