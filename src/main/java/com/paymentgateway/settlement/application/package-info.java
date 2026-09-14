/*
 * Application-layer service package.
 *
 * <p>Contains the payment orchestration service ({@link ChargeService})
 * and the ports (SPIs) that infrastructure adapters implement:
 * <ul>
 *   <li>{@link PaymentProcessor} — payment provider abstraction</li>
 * </ul>
 *
 * <p>The ChargeService owns the {@code @Transactional} boundaries:
 * <strong>TX1</strong> = idempotency reservation (deferred to Stage 4);
 * <strong>TX2</strong> = status transition + persistent state update.
 * The provider call happens <em>outside</em> TX1 but <em>inside</em> a
 * separate TX2, ensuring no database locks are held during the HTTP call.</p>
 */
package com.paymentgateway.settlement.application;
