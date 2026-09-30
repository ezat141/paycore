package com.paycore.payment.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

/**
 * The Redis fast path for idempotency (spec §7.3). It answers replays and turns away concurrent
 * duplicates without a Postgres round trip.
 *
 * <p><strong>It fails open, and that is safe only because it is not the guard.</strong> No method
 * here throws. Any failure (Redis unreachable or slow, an entry that cannot be read or makes no
 * sense, even a bug in this class) is logged and treated as a miss or an acquired lock, and the
 * request continues on the Postgres path, whose primary key still allows one payment per key. If
 * anything ever starts relying on this class for correctness, failing open becomes a
 * double-charge bug.
 */
@Component
public class IdempotencyCache {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCache.class);

    /**
     * Its own mapper, not Boot's, so the stored format depends on nothing in {@code spring.jackson}:
     * <ul>
     *   <li>lenient on unknown fields, so an entry written by a newer release (during a rolling
     *       deploy) still reads, where Boot's fail-on-unknown would make every such replay a miss;</li>
     *   <li>always compact, which {@link #RELEASE_IF_OWNER}'s substring match on
     *       {@code "owner":"…"} relies on: {@code indent-output} would silently break release.</li>
     * </ul>
     */
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** Deletes the lock only if we still own it: an expired holder must not delete a newer one. */
    private static final RedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current and string.find(current, ARGV[1], 1, true) then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    /** Deletes an unusable entry only if it is still the value we read, never whatever replaced it. */
    private static final RedisScript<Long> DELETE_IF_UNCHANGED = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    public record Entry(String state, String fingerprint, String owner, Integer responseStatus, String responseBody) {

        public boolean completed() {
            return "COMPLETED".equals(state);
        }

        /** A lock needs a fingerprint; a completed entry also needs the response it replays. */
        boolean usable() {
            if (fingerprint == null) {
                return false;
            }
            return completed() ? responseStatus != null && responseBody != null : "IN_PROGRESS".equals(state);
        }
    }

    /**
     * An entry as read, with the exact value stored, so a caller that finds it cannot be used
     * after all (a completed body that will not replay) can {@link #discard} precisely that value.
     */
    public record Cached(Entry entry, String stored) {
    }

    public enum LockResult { ACQUIRED, HELD, UNAVAILABLE }

    private final StringRedisTemplate redis;
    private final IdempotencyProperties properties;

    public IdempotencyCache(StringRedisTemplate redis, IdempotencyProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * The entry for the key, or empty on a miss, on any failure, or when the stored entry cannot be
     * used. An unusable entry is deleted: treated as a mere miss, it would still make every
     * {@link #tryLock} return HELD, and every request would get 409 until it expired.
     */
    public Optional<Cached> get(String tenant, String key) {
        String redisKey = redisKey(tenant, key);
        String value;
        try {
            value = redis.opsForValue().get(redisKey);
        } catch (RuntimeException e) {
            failed("read", e);
            return Optional.empty();
        }
        if (value == null) {
            return Optional.empty();
        }
        Entry entry;
        try {
            entry = JSON.readValue(value, Entry.class);
        } catch (RuntimeException e) {
            entry = null;
        }
        if (entry == null || !entry.usable()) {
            discard(redisKey, value);
            return Optional.empty();
        }
        return Optional.of(new Cached(entry, value));
    }

    /**
     * Deletes an entry that turned out to be unusable, and only if it is still the value read:
     * whatever has replaced it since is left alone. Never throws.
     */
    public void discard(String tenant, String key, Cached unusable) {
        discard(redisKey(tenant, key), unusable.stored());
    }

    public LockResult tryLock(String tenant, String key, String fingerprint, String owner) {
        try {
            String value = JSON.writeValueAsString(new Entry("IN_PROGRESS", fingerprint, owner, null, null));
            Boolean set = redis.opsForValue().setIfAbsent(redisKey(tenant, key), value, properties.lockTtl());
            return Boolean.TRUE.equals(set) ? LockResult.ACQUIRED : LockResult.HELD;
        } catch (RuntimeException e) {
            failed("lock", e);
            return LockResult.UNAVAILABLE;
        }
    }

    /** Stores the response to replay; {@code responseBody} is serialised here, so that can't fail the caller either. */
    public void complete(String tenant, String key, String fingerprint, int responseStatus, Object responseBody) {
        try {
            String body = JSON.writeValueAsString(responseBody);
            String value = JSON.writeValueAsString(new Entry("COMPLETED", fingerprint, null, responseStatus, body));
            redis.opsForValue().set(redisKey(tenant, key), value, properties.responseTtl());
        } catch (RuntimeException e) {
            failed("complete", e);
        }
    }

    public void release(String tenant, String key, String owner) {
        try {
            redis.execute(RELEASE_IF_OWNER, List.of(redisKey(tenant, key)), "\"owner\":\"" + owner + "\"");
        } catch (RuntimeException e) {
            failed("release", e);
        }
    }

    /**
     * The tenant is length-prefixed, so ("a:b", "c") and ("a", "b:c") stay apart. Without it
     * they would share a Redis key and one tenant could be replayed another's payment.
     */
    static String redisKey(String tenant, String key) {
        return "paycore:idem:" + tenant.length() + ":" + tenant + ":" + key;
    }

    private void discard(String redisKey, String value) {
        log.warn("discarding an unusable idempotency entry at {}; continuing on postgres", redisKey);
        try {
            redis.execute(DELETE_IF_UNCHANGED, List.of(redisKey), value);
        } catch (RuntimeException e) {
            failed("discard", e);
        }
    }

    private static void failed(String operation, RuntimeException e) {
        log.warn("idempotency cache {} failed; continuing on postgres alone: {}: {}",
                operation, e.getClass().getName(), e.getMessage());
    }
}
