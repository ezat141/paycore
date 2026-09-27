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
 * with the same reference cannot both create an authorization. Transitions use {@code compute}
 * on that same key, so a move is atomic with respect to other moves and to the authorization
 * that created the entry.
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

    Authorization capture(String authorizationId) {
        return move(authorizationId, AuthorizationStatus.CAPTURED);
    }

    Authorization voidAuthorization(String authorizationId) {
        return move(authorizationId, AuthorizationStatus.VOIDED);
    }

    /**
     * Repeating a completed move returns it unchanged, which is what makes PayCore's re-drive
     * of a stuck capture safe. Any other move out of a non-approved state is refused.
     */
    private Authorization move(String authorizationId, AuthorizationStatus target) {
        String reference = referenceById.get(authorizationId);
        if (reference == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such authorization");
        }
        return byReference.compute(reference, (ignored, current) -> {
            // referenceById is written inside authorize()'s computeIfAbsent, so it can briefly be
            // ahead of byReference. compute blocks until that entry is installed, so this is
            // defensive against future reordering.
            if (current == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such authorization");
            }
            if (current.status() == target) {
                return current;
            }
            if (current.status() != AuthorizationStatus.APPROVED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "cannot move " + current.status() + " to " + target);
            }
            return current.withStatus(target);
        });
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
