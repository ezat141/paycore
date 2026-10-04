package com.paycore.history.history;

import com.paycore.history.support.Events;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventParserTest {

    private final EventParser parser = new EventParser();

    @Test
    void aContractShapedEventParses() {
        UUID eventId = UUID.randomUUID();
        PaymentStatusChanged event = parser.parse(Events.json(eventId, UUID.randomUUID(), "acme", 1, "PENDING", "AUTHORIZED"));

        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.toStatus()).isEqualTo("AUTHORIZED");
    }

    @Test
    void anUnknownFieldIsIgnored() {
        String withExtra = Events.json(UUID.randomUUID(), UUID.randomUUID(), "acme", 0, null, "PENDING")
                .replace("\"tenant\"", "\"addedLater\":true,\"tenant\"");

        assertThat(parser.parse(withExtra).tenant()).isEqualTo("acme");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{ not json",
            "{}",
            "{\"eventId\":\"not-a-uuid\"}",
    })
    void malformedRecordsAreInvalid(String payload) {
        assertThatThrownBy(() -> parser.parse(payload)).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void anUnknownStatusIsInvalid() {
        String refunded = Events.json(UUID.randomUUID(), UUID.randomUUID(), "acme", 4, "CAPTURED", "REFUNDED");

        assertThatThrownBy(() -> parser.parse(refunded)).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void aBlankTenantIsInvalid() {
        String blank = Events.json(UUID.randomUUID(), UUID.randomUUID(), " ", 0, null, "PENDING");

        assertThatThrownBy(() -> parser.parse(blank)).isInstanceOf(InvalidEventException.class);
    }
}
