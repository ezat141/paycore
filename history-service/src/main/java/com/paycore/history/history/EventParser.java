package com.paycore.history.history;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

/**
 * Turns a record's value into an event, or rejects it as invalid. Records are read as strings
 * and parsed here rather than by a Kafka deserializer, so a malformed record becomes one
 * classified exception instead of a deserialization loop. Unknown fields are ignored, so a
 * producer may add fields before this consumer knows them.
 */
@Component
public class EventParser {

    private static final Set<String> STATUSES = Set.of("PENDING", "AUTHORIZED", "DECLINED", "UNKNOWN",
            "CAPTURING", "CAPTURED", "VOIDING", "VOIDED");

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public PaymentStatusChanged parse(String payload) {
        PaymentStatusChanged event;
        try {
            event = JSON.readValue(payload, PaymentStatusChanged.class);
        } catch (JacksonException e) {
            throw new InvalidEventException("unreadable event", e);
        }
        if (event == null || event.eventId() == null || event.paymentId() == null || event.occurredAt() == null) {
            throw new InvalidEventException("event is missing an id or a timestamp");
        }
        if (event.tenant() == null || event.tenant().isBlank()) {
            throw new InvalidEventException("event has no tenant");
        }
        if (event.version() < 0) {
            throw new InvalidEventException("negative version");
        }
        if (!STATUSES.contains(event.toStatus())
                || (event.fromStatus() != null && !STATUSES.contains(event.fromStatus()))) {
            throw new InvalidEventException("unknown status");
        }
        if (event.amount() <= 0 || event.currency() == null || !event.currency().matches("[A-Z]{3}")) {
            throw new InvalidEventException("invalid amount or currency");
        }
        return event;
    }
}
