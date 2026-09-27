package com.paycore.acquirer;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Card-token behaviours (spec §6). {@code tok_error} fails before anything is recorded;
 * {@code tok_timeout} is recorded as approved and only then stalls, so the caller times out on
 * a charge that really happened — the case PayCore has to survive.
 */
@RestController
@RequestMapping("/authorizations")
class AuthorizationController {

    private final AuthorizationBook book;
    private final SimulatorProperties properties;

    AuthorizationController(AuthorizationBook book, SimulatorProperties properties) {
        this.book = book;
        this.properties = properties;
    }

    @PostMapping
    AuthorizationResponse authorize(@RequestBody AuthorizationRequest request) throws InterruptedException {
        if (request.reference() == null || request.reference().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reference is required");
        }
        Thread.sleep(properties.latency());
        if ("tok_error".equals(request.cardToken())) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "simulated acquirer failure");
        }
        AuthorizationBook.Recorded recorded = book.authorize(request);
        // Only a new authorization stalls: a retry must find the stored one straight away.
        if (recorded.created() && "tok_timeout".equals(request.cardToken())) {
            Thread.sleep(properties.slowResponse());
        }
        return AuthorizationResponse.from(recorded.authorization());
    }

    @PostMapping("/{authorizationId}/capture")
    ActionResponse capture(@PathVariable String authorizationId) throws InterruptedException {
        Thread.sleep(properties.latency());
        return ActionResponse.from(book.capture(authorizationId));
    }

    @PostMapping("/{authorizationId}/void")
    ActionResponse voidAuthorization(@PathVariable String authorizationId) throws InterruptedException {
        Thread.sleep(properties.latency());
        return ActionResponse.from(book.voidAuthorization(authorizationId));
    }
}
