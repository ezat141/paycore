package com.paycore.payment.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("paycore.idempotency")
public record IdempotencyProperties(@DefaultValue("30s") Duration lockTtl) {
}
