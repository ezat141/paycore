package com.paycore.payment.payment;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The payment lifecycle. {@code CAPTURING} and {@code VOIDING} exist so a capture or void is
 * <em>claimed</em> in the database before the acquirer is called (spec §5.3); {@code UNKNOWN}
 * exists because a timeout is not a failure — the card may have been charged.
 */
public enum PaymentStatus {

    PENDING, AUTHORIZED, DECLINED, UNKNOWN, CAPTURING, CAPTURED, VOIDING, VOIDED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = new EnumMap<>(PaymentStatus.class);

    static {
        ALLOWED.put(PENDING, EnumSet.of(AUTHORIZED, DECLINED, UNKNOWN));
        // A re-drive (spec §7.5) turns an unknown outcome into a definite one.
        ALLOWED.put(UNKNOWN, EnumSet.of(AUTHORIZED, DECLINED));
        ALLOWED.put(AUTHORIZED, EnumSet.of(CAPTURING, VOIDING));
        ALLOWED.put(CAPTURING, EnumSet.of(CAPTURED));
        ALLOWED.put(VOIDING, EnumSet.of(VOIDED));
    }

    public boolean canMoveTo(PaymentStatus next) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(next);
    }
}
