/*
 * Simulated payment-provider adapters.
 *
 * <p>These adapters implement the {@link PaymentProcessor} SPI using
 * deterministic, in-process simulation. They are NOT real provider
 * integrations (no HTTP to external banks). Test scenarios are selected
 * via a token prefix convention documented in
 * {@code docs/provider-simulator.md}.</p>
 */
package com.paymentgateway.settlement.infrastructure.external.provider;
