package com.paycore.payment.payment;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The create body (spec §5.1). {@code amount} is an integer in minor units: 1999 is 19.99.
 * The database repeats the amount and currency rules as CHECK constraints.
 */
public record PaymentRequest(
        @NotNull(message = "is required")
        @Min(value = 1, message = "must be at least 1")
        @Max(value = 1_000_000_000L, message = "must be at most 1000000000")
        Long amount,

        @NotNull(message = "is required")
        @Pattern(regexp = "USD|EUR|GBP|EGP", message = "must be one of USD, EUR, GBP, EGP")
        String currency,

        @NotNull(message = "is required")
        @Pattern(regexp = "tok_[a-z0-9_]{1,60}", message = "must be a card token (tok_...), never a card number")
        String cardToken,

        @Size(max = 255, message = "must be at most 255 characters")
        String description) {
}
