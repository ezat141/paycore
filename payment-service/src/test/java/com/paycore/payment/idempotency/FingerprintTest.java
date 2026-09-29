package com.paycore.payment.idempotency;

import com.paycore.payment.payment.PaymentRequest;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FingerprintTest {

    private static final PaymentRequest BASE = new PaymentRequest(1999L, "USD", "tok_approve", null);

    /**
     * Pinned digests: a change to the canonical form would orphan every stored key, turning
     * every client retry into a 422. This test makes that change impossible to make by accident.
     */
    @Test
    void matchesTheDigestOfTheCanonicalForm() {
        // {"amount":1999,"currency":"USD","cardToken":"tok_approve","description":null}
        assertThat(Fingerprint.of(BASE))
                .isEqualTo("58753989a1c17c894c5db9f9ba69d0a1b30b9ce45f89b98f22ea25343b155304");
        // {"amount":1999,"currency":"USD","cardToken":"tok_approve","description":"Order 42"}
        assertThat(Fingerprint.of(new PaymentRequest(1999L, "USD", "tok_approve", "Order 42")))
                .isEqualTo("ee2818911aa881fe2ec98a5ae7e6bd363331f0580849b574415c39df05d94a9e");
    }

    @Test
    void everyFieldTakesPart() {
        Set<String> fingerprints = Set.of(
                Fingerprint.of(BASE),
                Fingerprint.of(new PaymentRequest(2000L, "USD", "tok_approve", null)),
                Fingerprint.of(new PaymentRequest(1999L, "EUR", "tok_approve", null)),
                Fingerprint.of(new PaymentRequest(1999L, "USD", "tok_other", null)),
                Fingerprint.of(new PaymentRequest(1999L, "USD", "tok_approve", "x")));

        assertThat(fingerprints).hasSize(5);
    }

    @Test
    void anAbsentDescriptionIsNotAnEmptyOne() {
        assertThat(Fingerprint.of(BASE))
                .isNotEqualTo(Fingerprint.of(new PaymentRequest(1999L, "USD", "tok_approve", "")));
    }
}
