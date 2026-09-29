package com.paycore.payment.error;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The platform's one error shape — {@code {"error", "status", "path", "detail"?}} — identical
 * to what GateKeeper and ledger-service render. Duplicated, not shared: the services share a
 * wire contract, not code.
 *
 * <p>{@code detail}, when present, is always text this service wrote, never an exception's
 * message, which risks leaking internals the caller has no business seeing.
 */
final class ErrorBody {

    private ErrorBody() {
    }

    static Map<String, Object> of(HttpStatus status, String path) {
        return of(status, path, null);
    }

    static Map<String, Object> of(HttpStatus status, String path, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", status.getReasonPhrase().toLowerCase(Locale.ROOT).replace(' ', '_'));
        body.put("status", status.value());
        body.put("path", path);
        if (detail != null) {
            body.put("detail", detail);
        }
        return body;
    }

    static Map<String, Object> of(HttpStatus status, String path, String detail, Map<String, Object> extras) {
        Map<String, Object> body = of(status, path, detail);
        body.putAll(extras);
        return body;
    }
}
