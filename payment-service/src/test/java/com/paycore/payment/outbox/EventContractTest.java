package com.paycore.payment.outbox;

import com.paycore.payment.support.PaymentServiceTest;
import com.paycore.payment.support.Tokens;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.paycore.payment.support.Requests.body;
import static com.paycore.payment.support.Requests.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * I13: the payload payment-service writes has exactly the contract's keys, with the contract's
 * JSON types wherever both values are non-null. history-service tests the same file.
 */
class EventContractTest extends PaymentServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void everyEventMatchesTheContract() throws Exception {
        Map<String, Object> contract = JsonMapper.builder().build()
                .readValue(Files.readString(Path.of("../contracts/payment-status-changed.json")), Map.class);
        String id = read(create(Tokens.writer("acme"), "order-1", body(1999, "tok_approve")), "$.id");
        mockMvc.perform(post("/payments/{id}/capture", id).with(Tokens.writer("acme")));

        List<Map<String, Object>> events = outboxEvents(id);
        assertThat(events).hasSize(4);
        for (Map<String, Object> event : events) {
            assertThat(event.keySet()).containsExactlyInAnyOrderElementsOf(contract.keySet());
            for (String key : contract.keySet()) {
                Object expected = contract.get(key);
                Object actual = event.get(key);
                if (expected != null && actual != null) {
                    assertThat(jsonType(actual)).as(key).isEqualTo(jsonType(expected));
                }
            }
        }
    }

    private static String jsonType(Object value) {
        if (value instanceof Number) return "number";
        if (value instanceof String) return "string";
        if (value instanceof Boolean) return "boolean";
        return value.getClass().getSimpleName();
    }
}
