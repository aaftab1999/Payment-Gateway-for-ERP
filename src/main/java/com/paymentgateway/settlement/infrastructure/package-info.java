/*
 * Infrastructure layer: concrete adapters for persistence (JPA/Flyway),
 * messaging (Kafka), caching (Redis) and external HTTP clients.
 *
 * <p>Stage 2 contributes only the infrastructure configuration beans
 * (Kafka admin, Redis connection factory) and the Flyway migrations.</p>
 */
package com.paymentgateway.settlement.infrastructure;
