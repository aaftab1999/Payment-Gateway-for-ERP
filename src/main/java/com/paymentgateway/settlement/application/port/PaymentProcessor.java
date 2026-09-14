package com.paymentgateway.settlement.application.port;

import com.paymentgateway.settlement.domain.payment.ProviderResult;

import java.util.UUID;

/**
 * Payment processor SPI (port).
 *
 * <p><strong>Why this abstraction exists:</strong>
 * The payment domain's workflow (state machine, validation, status
 * transitions) is identical regardless of whether the payment flows
 * through UPI, a credit card, or net-banking. The provider-specific
 * HTTP protocol, authentication, and response parsing are all
 * implementation details that belong in the adapter layer
 * ({@code infrastructure.external.provider}).</p>
 *
 * <p><strong>Domain boundary:</strong> This interface is in the
 * application (port) package, not in domain or infrastructure, so the
 * domain payment aggregate never depends on HTTP clients or provider
 * SDKs.</p>
 *
 * <p><strong>How a real provider could be added:</strong> Create a new
 * class implementing this interface (e.g. {@code StripePaymentProcessor})
 * and register it as a Spring bean keyed by {@link com.paymentgateway.settlement.domain.payment.PaymentMethodType}.
 * The ChargeService already selects the processor by method type —
 * no workflow changes needed.</p>
 *
 * @param paymentToken  the tokenized/simulated payment credential
 * @param amountMinor   amount in minor units (paise/cents)
 * @param currency      ISO 4217 currency code
 * @param correlationId trace ID for the provider call
 * @return the result from the provider
 */
public interface PaymentProcessor {
    ProviderResult process(String paymentToken, long amountMinor, String currency, UUID correlationId);
}
