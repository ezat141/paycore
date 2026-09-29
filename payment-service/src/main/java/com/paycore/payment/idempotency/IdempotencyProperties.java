package com.paycore.payment.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param lockTtl     the lease on a key while a request runs, in Postgres and in Redis alike
 * @param responseTtl how long Redis keeps a completed response; Postgres keeps it indefinitely
 */
@ConfigurationProperties("paycore.idempotency")
public record IdempotencyProperties(
        @DefaultValue("30s") Duration lockTtl,
        @DefaultValue("24h") Duration responseTtl) {
}
