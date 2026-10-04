package com.paycore.history.history;

import com.paycore.history.support.Events;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;
import java.util.stream.Stream;

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

    /** Each case breaks exactly one rule, so each guard in the parser is pinned on its own. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidEvents")
    void eachInvalidFieldIsRejected(String broken, String payload) {
        assertThatThrownBy(() -> parser.parse(payload)).isInstanceOf(InvalidEventException.class);
    }

    static Stream<Arguments> invalidEvents() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String valid = Events.json(eventId, paymentId, "acme", 1, "PENDING", "AUTHORIZED");
        return Stream.of(
                Arguments.of("null eventId", valid.replace("\"eventId\":\"" + eventId + "\"", "\"eventId\":null")),
                Arguments.of("null paymentId", valid.replace("\"paymentId\":\"" + paymentId + "\"", "\"paymentId\":null")),
                Arguments.of("null occurredAt", valid.replace("\"occurredAt\":\"2026-10-03T10:15:30.123456Z\"", "\"occurredAt\":null")),
                Arguments.of("negative version", Events.json(eventId, paymentId, "acme", -1, "PENDING", "AUTHORIZED")),
                Arguments.of("unknown fromStatus", Events.json(eventId, paymentId, "acme", 4, "REFUNDED", "CAPTURED")),
                Arguments.of("zero amount", valid.replace("\"amount\":1999", "\"amount\":0")),
                Arguments.of("negative amount", valid.replace("\"amount\":1999", "\"amount\":-5")),
                Arguments.of("lowercase currency", valid.replace("\"currency\":\"USD\"", "\"currency\":\"usd\"")),
                Arguments.of("null currency", valid.replace("\"currency\":\"USD\"", "\"currency\":null")),
                // Jackson would otherwise truncate 19.99 to 19: money must never be silently rounded
                Arguments.of("fractional amount", valid.replace("\"amount\":1999", "\"amount\":19.99")),
                // ...and would otherwise read the string "1" as the number 1
                Arguments.of("version as a string", valid.replace("\"version\":1", "\"version\":\"1\"")));
    }

    @Test
    void aBlankTenantIsInvalid() {
        String blank = Events.json(UUID.randomUUID(), UUID.randomUUID(), " ", 0, null, "PENDING");

        assertThatThrownBy(() -> parser.parse(blank)).isInstanceOf(InvalidEventException.class);
    }
}
