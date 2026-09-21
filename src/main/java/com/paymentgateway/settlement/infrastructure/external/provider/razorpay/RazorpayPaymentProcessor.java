package com.paymentgateway.settlement.infrastructure.external.provider.razorpay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentgateway.settlement.application.port.PaymentProcessor;
import com.paymentgateway.settlement.config.RazorpayProperties;
import com.paymentgateway.settlement.domain.payment.ProviderResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Razorpay Orders API integration implementing the {@link PaymentProcessor} SPI.
 *
 * <p><strong>Flow:</strong></p>
 * <ol>
 *   <li>The gateway creates a Razorpay order via {@code POST /v1/orders}.</li>
 *   <li>The order is created with {@code payment_capture: 1} (automatic capture)
 *       so the customer's payment method is charged at checkout.</li>
 *   <li>The {@code providerIdempotencyKey} is sent as the Razorpay {@code receipt}
 *       field, which acts as a server-side idempotency key — a duplicate
 *       request with the same receipt returns the existing order.</li>
 *   <li>The order is created in {@code "created"} status; the actual payment
 *       capture happens asynchronously on the customer's frontend. The gateway
 *       returns {@link ProviderResult.Type#UNKNOWN} to place the payment in a
 *       non-terminal state awaiting webhook resolution.</li>
 *   <li>When Razorpay fires a webhook ({@code payment.captured},
 *       {@code payment.failed}, {@code order.paid}), the
 *       {@link com.paymentgateway.settlement.application.service.ChargeService}
 *       resolves the payment to SUCCEEDED or FAILED.</li>
 * </ol>
 *
 * <p><strong>Receipt truncation:</strong> Razorpay requires {@code receipt} to be
 * ≤ 40 characters. The gateway's {@code providerIdempotencyKey} is formatted as
 * {@code prov_<UUID>} (41 chars). The UUID portion (36 chars) is used directly,
 * preserving uniqueness and idempotency.</p>
 */
@Component
@ConditionalOnProperty(name = "app.razorpay.enabled", havingValue = "true")
public class RazorpayPaymentProcessor implements PaymentProcessor {

    private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentProcessor.class);

    private static final int RECEIPT_MAX_LEN = 40;
    private static final String PROVIDER_KEY_PREFIX = "prov_";

    private final RestClient restClient;
    private final RazorpayProperties properties;
    private final ObjectMapper objectMapper;

    public RazorpayPaymentProcessor(final RestClient razorpayRestClient,
                                    final RazorpayProperties properties,
                                    final ObjectMapper objectMapper) {
        this.restClient = razorpayRestClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ProviderResult process(
            final String paymentToken,
            final long amountMinor,
            final String currency,
            final UUID correlationId,
            final String providerIdempotencyKey) {

        log.info("Creating Razorpay order: amount={}, currency={}, correlationId={}, receipt={}",
                maskAmount(amountMinor), currency, correlationId, maskKey(providerIdempotencyKey));

        String receipt = toRazorpayReceipt(providerIdempotencyKey);

        RazorpayOrderRequest request = new RazorpayOrderRequest(
                amountMinor,
                currency,
                receipt,
                Map.of("correlationId", correlationId.toString()),
                1
        );

        try {
            RazorpayOrderResponse response = restClient.post()
                    .uri("/orders")
                    .body(request)
                    .retrieve()
                    .body(RazorpayOrderResponse.class);

            if (response == null) {
                log.warn("Razorpay order creation returned empty response for receipt={}",
                        maskKey(providerIdempotencyKey));
                return ProviderResult.unknown(
                        "EMPTY_RESPONSE",
                        "Razorpay returned an empty response for order creation");
            }

            log.info("Razorpay order created: orderId={}, status={}, receipt={}",
                    maskId(response.id()), response.status(), maskKey(response.receipt()));

            return new ProviderResult(
                    ProviderResult.Type.UNKNOWN,
                    response.id(),
                    "ORDER_CREATED",
                    "Razorpay order " + response.id() + " created; awaiting customer payment");

        } catch (RestClientResponseException e) {
            log.error("Razorpay API error for receipt={}: {} {}",
                    maskKey(providerIdempotencyKey), e.getStatusCode(), e.getMessage());
            return mapHttpError(e, providerIdempotencyKey);
        } catch (Exception e) {
            log.error("Provider call failed for receipt={}: {}",
                    maskKey(providerIdempotencyKey), e.getMessage(), e);
            return ProviderResult.technicalFailure(
                    "PROVIDER_EXCEPTION",
                    "Razorpay API call failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Converts the gateway's {@code providerIdempotencyKey} (format
     * {@code prov_<UUID>, 41 chars) to a Razorpay-compliant receipt
     * (≤ 40 chars) by stripping the {@code prov_} prefix and falling back
     * to truncation if the key is unusually long.
     */
    private static String toRazorpayReceipt(final String providerIdempotencyKey) {
        if (providerIdempotencyKey == null || providerIdempotencyKey.isBlank()) {
            return UUID.randomUUID().toString();
        }
        if (providerIdempotencyKey.startsWith(PROVIDER_KEY_PREFIX)) {
            String uuid = providerIdempotencyKey.substring(PROVIDER_KEY_PREFIX.length());
            return uuid.length() <= RECEIPT_MAX_LEN
                    ? uuid
                    : uuid.substring(0, RECEIPT_MAX_LEN);
        }
        return providerIdempotencyKey.length() <= RECEIPT_MAX_LEN
                ? providerIdempotencyKey
                : providerIdempotencyKey.substring(0, RECEIPT_MAX_LEN);
    }

    private static ProviderResult mapHttpError(final RestClientResponseException e,
                                               final String providerIdempotencyKey) {
        int code = e.getStatusCode().value();
        if (code == 400 || code == 409) {
            return ProviderResult.declined(
                    "RAZORPAY_REJECTED",
                    "Razorpay rejected order creation (HTTP " + code + ")");
        }
        if (code == 401 || code == 403) {
            return ProviderResult.technicalFailure(
                    "AUTH_ERROR",
                    "Razorpay authentication failed (HTTP " + code + ")");
        }
        if (code >= 500) {
            return ProviderResult.technicalFailure(
                    "RAZORPAY_ERROR",
                    "Razorpay server error (HTTP " + code + ")");
        }
        return ProviderResult.unknown(
                "HTTP_" + code,
                "Unexpected HTTP response from Razorpay: " + code);
    }

    private static String maskAmount(final long amountMinor) {
        return "*" + (amountMinor % 1000) + "minor";
    }

    private static String maskKey(final String key) {
        if (key == null || key.isBlank()) return "***";
        return key.length() <= 8 ? "***" : key.substring(0, 6) + "*****";
    }

    private static String maskId(final String id) {
        if (id == null || id.isBlank()) return "***";
        return id.length() <= 8 ? "***" : id.substring(0, 8) + "...";
    }
}
