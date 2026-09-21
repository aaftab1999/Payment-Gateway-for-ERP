package com.paymentgateway.settlement.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentgateway.settlement.application.service.ChargeService;
import com.paymentgateway.settlement.config.RazorpayProperties;
import com.paymentgateway.settlement.domain.payment.ProviderResult;
import com.paymentgateway.settlement.infrastructure.external.provider.razorpay.RazorpaySignatureVerifier;
import com.paymentgateway.settlement.infrastructure.external.provider.razorpay.RazorpayWebhookEvent;

import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

/**
 * Webhook endpoint for Razorpay payment notifications.
 *
 * <p>Registered at {@code POST /api/v1/webhooks/razorpay} and only active when
 * {@code app.razorpay.enabled=true}. Every request is verified against the
 * {@code X-Razorpay-Signature} header using HMAC-SHA256 and the configured
 * webhook secret before any domain state is updated.</p>
 *
 * <p><strong>Event handling:</strong></p>
 * <ul>
 *   <li>{@code order.paid} / {@code payment.captured} → payment transitions to SUCCEEDED</li>
 *   <li>{@code payment.failed} → payment transitions to FAILED</li>
 *   <li>{@code payment.authorized} — no state change (order created, awaiting capture)</li>
 *   <li>Unknown events are logged and acknowledged</li>
 * </ul>
 *
 * <p><strong>Idempotency:</strong> {@link ChargeService#onProviderWebhook} is
 * idempotent — duplicate webhooks for a terminal payment are silently skipped.</p>
 */
@RestController
@RequestMapping("/webhooks")
@ConditionalOnProperty(name = "app.razorpay.enabled", havingValue = "true")
public class RazorpayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookController.class);

    private final ChargeService chargeService;
    private final RazorpayProperties properties;
    private final ObjectMapper objectMapper;

    public RazorpayWebhookController(final ChargeService chargeService,
                                     final RazorpayProperties properties,
                                     final ObjectMapper objectMapper) {
        this.chargeService = chargeService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/razorpay")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody final String rawBody,
            @RequestHeader(value = "X-Razorpay-Signature", required = true)
            @NotBlank final String signature) {

        if (!RazorpaySignatureVerifier.verify(signature, rawBody.getBytes(StandardCharsets.UTF_8),
                properties.getWebhookSecret())) {
            log.warn("Invalid Razorpay webhook signature — rejecting request");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            RazorpayWebhookEvent event = objectMapper.readValue(rawBody, RazorpayWebhookEvent.class);
            log.info("Received Razorpay webhook: event={}, orderId={}",
                    event.event(),
                    event.payload() != null && event.payload().payment() != null
                            ? event.payload().payment().orderId() : "unknown");

            handleEvent(event);

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to process Razorpay webhook: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private void handleEvent(final RazorpayWebhookEvent event) {
        if (event.payload() == null || event.payload().payment() == null) {
            log.debug("Webhook has no payment payload — ignoring: event={}", event.event());
            return;
        }

        String orderId = event.payload().payment().orderId();
        if (orderId == null || orderId.isBlank()) {
            log.warn("Webhook payload has no order_id — cannot correlate: event={}", event.event());
            return;
        }

        ProviderResult result = switch (event.event()) {
            case "payment.captured", "order.paid" ->
                    ProviderResult.success(orderId);
            case "payment.failed" ->
                    ProviderResult.declined(
                            event.payload().payment().errorCode() != null
                                    ? event.payload().payment().errorCode()
                                    : "PAYMENT_FAILED",
                            event.payload().payment().errorDescription() != null
                                    ? event.payload().payment().errorDescription()
                                    : "Razorpay reported payment failed");
            case "payment.authorized" -> {
                log.info("Payment authorized (not yet captured) for order={} — no state change", orderId);
                yield null;
            }
            default -> {
                log.debug("Unhandled Razorpay event type: {} for order={}", event.event(), orderId);
                yield null;
            }
        };

        if (result != null) {
            chargeService.onProviderWebhook(orderId, result);
        }
    }
}
