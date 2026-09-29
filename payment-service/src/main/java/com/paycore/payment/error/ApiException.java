package com.paycore.payment.error;

import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.UUID;

/**
 * A refusal this service decided on, carrying the status and the {@code detail} we wrote.
 * These are expected outcomes, not faults, so no stack trace is captured.
 */
public final class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String detail;
    private final Map<String, Object> extras;
    private final boolean retryLater;

    private ApiException(HttpStatus status, String detail, Map<String, Object> extras, boolean retryLater) {
        super(detail, null, false, false);
        this.status = status;
        this.detail = detail;
        this.extras = extras;
        this.retryLater = retryLater;
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, detail, Map.of(), false);
    }

    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "no such payment", Map.of(), false);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, detail, Map.of(), false);
    }

    /** 409 with {@code Retry-After: 1}: the thing asked for is happening; ask again shortly. */
    public static ApiException inProgress(String detail) {
        return new ApiException(HttpStatus.CONFLICT, detail, Map.of(), true);
    }

    public static ApiException keyReused() {
        // valueOf, not a constant: HttpStatus.UNPROCESSABLE_ENTITY is deprecated in Framework 7 in
        // favour of UNPROCESSABLE_CONTENT (same 422, not a rename); the number is the contract,
        // not either constant's name.
        return new ApiException(HttpStatus.valueOf(422),
                "Idempotency-Key was already used with a different request", Map.of(), false);
    }

    /** The acquirer gave no definite answer. {@code paymentId} is present on authorize only. */
    public static ApiException badGateway(String detail, UUID paymentId) {
        Map<String, Object> extras = paymentId == null
                ? Map.of()
                : Map.<String, Object>of("paymentId", paymentId.toString());
        return new ApiException(HttpStatus.BAD_GATEWAY, detail, extras, false);
    }

    /** The acquirer refused a capture or void with a 4xx. The payment is left mid-transition. */
    public static ApiException acquirerRefused(String verb) {
        return new ApiException(HttpStatus.BAD_GATEWAY,
                "acquirer refused the " + verb + "; the payment needs reconciliation", Map.of(), false);
    }

    /** The acquirer refused an authorization with a 4xx. The payment is left PENDING. */
    public static ApiException acquirerRefused(String verb, UUID paymentId) {
        return new ApiException(HttpStatus.BAD_GATEWAY,
                "acquirer refused the " + verb + "; the payment needs reconciliation",
                Map.of("paymentId", paymentId.toString()), false);
    }

    public HttpStatus status() {
        return status;
    }

    public String detail() {
        return detail;
    }

    public Map<String, Object> extras() {
        return extras;
    }

    public boolean retryLater() {
        return retryLater;
    }
}
