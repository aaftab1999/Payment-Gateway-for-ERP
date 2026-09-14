/*
 * Application-service layer: use-case orchestration.
 *
 * <p>This package contains the {@link ChargeService} which coordinates:
 * <ul>
 *   <li>domain model creation (Payment.create)</li>
 *   <li>provider call via the {@link PaymentProcessor} SPI</li>
 *   <li>state-machine transitions via {@link PaymentStateEngine}</li>
 *   <li>persistence via {@link PaymentRepository}</li>
 * </ul>
 *
 * <p><strong>Transaction boundaries:</strong></p>
 * <pre>
 *   TX1 — create payment (CREATED): insert PaymentEntity, commit
 *   provider call — OUTSIDE transaction (no DB locks held during HTTP)
 *   TX2 — apply result: SELECT FOR UPDATE payment, transition, update, commit
 * </pre>
 * </p>
 */
package com.paymentgateway.settlement.application.service;
