package com.paycore.payment.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param transitionStaleAfter how long a claimed capture/void may sit unanswered before a retry
 *                             may re-drive it. Well past the acquirer read timeout, so a
 *                             request still waiting on the acquirer is never raced by a retry.
 */
@ConfigurationProperties("paycore.payments")
public record PaymentProperties(@DefaultValue("30s") Duration transitionStaleAfter) {
}
