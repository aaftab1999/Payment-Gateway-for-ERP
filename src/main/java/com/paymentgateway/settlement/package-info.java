/*
 * Base package for the Payment Gateway & Settlement Core Engine.
 *
 * <p>This project is a single Spring Boot monolith organised using a
 * Hexagonal / Ports-and-Adapters layout:</p>
 * <pre>
 *   api               REST controllers, DTOs, mappers
 *   application       use cases & orchestration (owns @Transactional)
 *   domain            pure domain (Payment aggregate, Money, Ledger, State Machine)
 *   infrastructure    adapters (JPA, Kafka, Redis, HTTP clients)
 *   config            cross-cutting bean wiring
 *   observability     MDC, metrics, correlation-id
 *   common            shared exceptions / utilities
 * </pre>
 *
 * <p>The external ERP is NOT part of this repository — it owns customers,
 * invoices, bills and outstanding balances.  This gateway only owns
 * payment processing, its ledger, events and reconciliation.</p>
 */
package com.paymentgateway.settlement;
