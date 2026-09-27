package com.paycore.payment.payment;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every ordered pair of statuses, checked against the edges drawn in spec §5.2 plus the two
 * re-drive edges from §7.5. Anything not listed here must be refused.
 */
class PaymentStatusTest {

    private static final Set<String> ALLOWED = Set.of(
            "PENDING->AUTHORIZED", "PENDING->DECLINED", "PENDING->UNKNOWN",
            "UNKNOWN->AUTHORIZED", "UNKNOWN->DECLINED",
            "AUTHORIZED->CAPTURING", "AUTHORIZED->VOIDING",
            "CAPTURING->CAPTURED",
            "VOIDING->VOIDED");

    static Stream<Arguments> everyPair() {
        return Arrays.stream(PaymentStatus.values())
                .flatMap(from -> Arrays.stream(PaymentStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPair")
    void onlyTheDrawnTransitionsAreAllowed(PaymentStatus from, PaymentStatus to) {
        assertThat(from.canMoveTo(to)).isEqualTo(ALLOWED.contains(from + "->" + to));
    }
}
