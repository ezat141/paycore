package com.paycore.history.history;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * A payment's status changes, in version order (spec §6.5). Eventually consistent: a payment
 * created a moment ago may have no history yet, and answers 404 like one that does not exist.
 */
@RestController
@RequestMapping(path = "/payments", produces = MediaType.APPLICATION_JSON_VALUE)
public class HistoryController {

    private final HistoryStore store;

    public HistoryController(HistoryStore store) {
        this.store = store;
    }

    @GetMapping("/{id}/history")
    public List<HistoryEntry> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        // The tenant is a non-blank String: PaymentsAuthorization refuses anything else first.
        List<HistoryEntry> entries = store.find(jwt.getClaimAsString("tenant"), id);
        if (entries.isEmpty()) {
            throw new NoHistoryException();
        }
        return entries;
    }
}
