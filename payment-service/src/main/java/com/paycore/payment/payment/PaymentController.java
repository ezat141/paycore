package com.paycore.payment.payment;

import com.paycore.payment.error.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    /** 1–255 printable ASCII characters, no space (0x21–0x7E). */
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");

    private final PaymentCreation creation;
    private final PaymentRepository payments;

    public PaymentController(PaymentCreation creation, PaymentRepository payments) {
        this.creation = creation;
        this.payments = payments;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PaymentResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                  @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                  @Valid @RequestBody PaymentRequest request) {
        requireWellFormed(idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(creation.create(tenantOf(jwt), request));
    }

    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public PaymentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return payments.findByIdAndTenant(id, tenantOf(jwt))
                .map(PaymentResponse::from)
                .orElseThrow(ApiException::notFound);
    }

    private static void requireWellFormed(String idempotencyKey) {
        if (!IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw ApiException.badRequest("Idempotency-Key must be 1-255 printable ASCII characters, without spaces");
        }
    }

    /** Always present: PaymentsAuthorization refuses a token without one before any handler runs. */
    private static String tenantOf(Jwt jwt) {
        return jwt.getClaimAsString("tenant");
    }
}
