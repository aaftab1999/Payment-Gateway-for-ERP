package com.paymentgateway.settlement.api.controller;

import com.paymentgateway.settlement.api.dto.CreatePaymentRequest;
import com.paymentgateway.settlement.api.dto.PaymentDtoMapper;
import com.paymentgateway.settlement.api.dto.PaymentResponse;
import com.paymentgateway.settlement.application.service.ChargeService;
import com.paymentgateway.settlement.domain.payment.Payment;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST controller for payment operations.
 *
 * <p><strong>Endpoints:</strong></p>
 * <ul>
 *   <li>{@code POST /api/v1/payments} — create &amp; process a payment</li>
 *   <li>{@code GET /api/v1/payments/{paymentId}} — retrieve a payment</li>
 *   <li>{@code GET /api/v1/payments/by-bill/{billReference}?merchantId=...} — retrieve all payments for a bill</li>
 * </ul>
 *
 * <p><strong>Deviation from Stage 1 architecture:</strong> The architecture
 * doc specified {@code POST /api/v1/payments/charge}. Stage 3 instructions
 * specified {@code POST /api/v1/payments}. We follow the Stage 3 instruction.
 * The {@code /charge} suffix can be added as an alias in a future stage if
 * the external ERP requires it.</p>
 *
 * <p><strong>Security:</strong> Payment tokens are never returned in
 * responses.</p>
 */
@RestController
@RequestMapping("/payments")
@Slf4j
public class PaymentController {

    private final ChargeService chargeService;

    public PaymentController(final ChargeService chargeService) {
        this.chargeService = chargeService;
    }

    /**
     * {@code POST /api/v1/payments}
     *
     * Creates a payment in CREATED, submits it to the simulated provider,
     * and applies the result. Returns the current payment status.
     *
     * @param request validated request body
     * @param correlationId from X-Correlation-Id header (or generated)
     * @return 201 Created with payment response
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @Valid @RequestBody final CreatePaymentRequest body,
            final HttpServletRequest servletRequest,
            @RequestHeader(value = "X-Correlation-Id", required = false) final String correlationId,
            @RequestHeader(value = "X-Base-Url", required = false) final String baseUrl) {

        // The correlation ID is resolved by CorrelationIdFilter and exposed as
        // a request attribute. Reading it here guarantees the response body
        // uses the same ID that was echoed in the response header.
        UUID corrId = (UUID) servletRequest.getAttribute("correlationId");
        if (corrId == null) {
            // Fallback for tests that bypass the filter (e.g. MockMvc without
            // the filter chain). Replicate the filter's policy exactly.
            corrId = resolveCorrelationId(correlationId);
        }
        String base = baseUrl != null ? baseUrl : "/api/v1";

        log.info("Received payment request: merchant={}, billRef={}, amount={}",
                body.merchantId(), body.billRef(), body.amount());

        Payment payment = chargeService.charge(
                body.merchantId(),
                body.customerRef(),
                body.billRef(),
                body.amount(),
                body.currency(),
                body.paymentMethod(),
                body.paymentToken(),
                corrId
        );

        PaymentResponse response = PaymentDtoMapper.toResponse(payment, base);
        return ResponseEntity.status(201).body(response);
    }

    /**
     * {@code GET /api/v1/payments/{paymentId}}
     *
     * Returns the current status and non-sensitive details of a payment.
     * Never returns the payment token.
     */
    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getPayment(
            @PathVariable final UUID paymentId,
            @RequestHeader(value = "X-Base-Url", required = false) final String baseUrl,
            @RequestHeader(value = "X-Correlation-Id", required = false) final String correlationId) {

        String base = baseUrl != null ? baseUrl : "/api/v1";
        Payment payment = chargeService.getPayment(paymentId);
        return ResponseEntity.ok(PaymentDtoMapper.toResponse(payment, base));
    }

    /**
     * {@code GET /api/v1/payments/by-bill/{billReference}?merchantId=...}
     *
     * Returns all payments for a given ERP bill reference within a merchant.
     * Uses a single SQL query (no N+1).
     */
    @GetMapping("/by-bill/{billReference}")
    public ResponseEntity<List<PaymentResponse>> getPaymentsByBillReference(
            @PathVariable final String billReference,
            @RequestParam final String merchantId,
            @RequestHeader(value = "X-Base-Url", required = false) final String baseUrl,
            @RequestHeader(value = "X-Correlation-Id", required = false) final String correlationId) {

        String base = baseUrl != null ? baseUrl : "/api/v1";
        List<Payment> payments = chargeService.getPaymentsByBillRef(merchantId, billReference);
        List<PaymentResponse> responses = payments.stream()
                .map(p -> PaymentDtoMapper.toResponse(p, base))
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    /**
     * Resolves the correlation ID for a request, matching the policy used by
     * {@link CorrelationIdFilter}. Used as a fallback when the filter has not
     * run (e.g. unit tests that bypass the filter chain).
     *
     * <p>Priority: use the client-supplied {@code X-Correlation-Id} header if
     * present and valid; otherwise generate a new UUID.</p>
     */
    private static UUID resolveCorrelationId(final String headerValue) {
        if (headerValue != null && !headerValue.isBlank()) {
            try {
                return UUID.fromString(headerValue);
            } catch (IllegalArgumentException e) {
                // Malformed header — generate a new UUID.
            }
        }
        return UUID.randomUUID();
    }
}
