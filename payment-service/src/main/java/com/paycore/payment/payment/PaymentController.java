package com.paycore.payment.payment;

import com.paycore.payment.error.ApiException;
import jakarta.validation.Valid;
import org.springframework.data.domain.Limit;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
// produces at class level so every mapping, including ones added later, refuses a non-JSON
// Accept with 406 before its handler runs, and so before any acquirer call.
@RequestMapping(path = "/payments", produces = MediaType.APPLICATION_JSON_VALUE)
public class PaymentController {

    /** 1–255 printable ASCII characters, no space (0x21–0x7E). */
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");

    private final PaymentCreation creation;
    private final PaymentTransitions transitions;
    private final PaymentRepository payments;

    public PaymentController(PaymentCreation creation, PaymentTransitions transitions, PaymentRepository payments) {
        this.creation = creation;
        this.transitions = transitions;
        this.payments = payments;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                  @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                  @Valid @RequestBody PaymentRequest request) {
        requireWellFormed(idempotencyKey);
        CreateResult result = creation.create(tenantOf(jwt), idempotencyKey, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status());
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(result.body());
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return payments.findByIdAndTenant(id, tenantOf(jwt))
                .map(PaymentResponse::from)
                .orElseThrow(ApiException::notFound);
    }

    @PostMapping("/{id}/capture")
    public PaymentResponse capture(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return transitions.capture(tenantOf(jwt), id);
    }

    @PostMapping("/{id}/void")
    public PaymentResponse voidPayment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return transitions.voidPayment(tenantOf(jwt), id);
    }

    @GetMapping
    public List<PaymentResponse> list(@AuthenticationPrincipal Jwt jwt,
                                      @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > 100) {
            throw ApiException.badRequest("limit must be between 1 and 100");
        }
        return payments.findByTenantOrderByCreatedAtDescIdDesc(tenantOf(jwt), Limit.of(limit)).stream()
                .map(PaymentResponse::from)
                .toList();
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
