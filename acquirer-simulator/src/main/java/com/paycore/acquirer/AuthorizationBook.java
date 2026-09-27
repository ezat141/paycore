package com.paycore.acquirer;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory authorizations, keyed by the merchant's reference. {@code computeIfAbsent} makes
 * "have I seen this reference?" and "record it" one atomic step, so two concurrent requests
 * with the same reference cannot both create an authorization.
 */
@Component
class AuthorizationBook {

    record Recorded(Authorization authorization, boolean created) {
    }

    private final Map<String, Authorization> byReference = new ConcurrentHashMap<>();
    private final Map<String, String> referenceById = new ConcurrentHashMap<>();

    Recorded authorize(AuthorizationRequest request) {
        boolean[] created = {false};
        Authorization authorization = byReference.computeIfAbsent(request.reference(), reference -> {
            created[0] = true;
            Authorization fresh = decide(request);
            referenceById.put(fresh.authorizationId(), reference);
            return fresh;
        });
        if (!authorization.sameRequestAs(request)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "reference already used for a different authorization");
        }
        return new Recorded(authorization, created[0]);
    }

    /** Whether {@code reference} has been recorded yet — a read-only check, no side effect. */
    boolean isRecorded(String reference) {
        return byReference.containsKey(reference);
    }

    private static Authorization decide(AuthorizationRequest request) {
        String id = "auth_" + UUID.randomUUID();
        if ("tok_decline_funds".equals(request.cardToken())) {
            return new Authorization(id, request.reference(), request.amount(), request.currency(),
                    request.cardToken(), AuthorizationStatus.DECLINED, "insufficient_funds");
        }
        return new Authorization(id, request.reference(), request.amount(), request.currency(),
                request.cardToken(), AuthorizationStatus.APPROVED, null);
    }
}
