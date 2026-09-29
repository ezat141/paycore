package com.paycore.payment.idempotency;

import com.paycore.payment.payment.PaymentRequest;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SHA-256 of the request in a fixed canonical form (spec §7.2): fields in a fixed order, an
 * absent description as {@code null}. Two requests with the same fingerprint are the same
 * request, whatever order or whitespace the client sent them in.
 *
 * <p>Uses its own mapper, not Boot's: a global Jackson setting changed later must never alter
 * stored fingerprints, or every retry against an existing key would become a 422.
 */
public final class Fingerprint {

    private static final JsonMapper CANONICAL = JsonMapper.builder().build();

    private Fingerprint() {
    }

    public static String of(PaymentRequest request) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("amount", request.amount());
        canonical.put("currency", request.currency());
        canonical.put("cardToken", request.cardToken());
        canonical.put("description", request.description());
        byte[] bytes = CANONICAL.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(sha256(bytes));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every JVM ships SHA-256", impossible);
        }
    }
}
