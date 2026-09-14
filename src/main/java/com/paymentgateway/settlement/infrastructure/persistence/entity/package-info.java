/*
 * Persistence layer: JPA entities, repositories, and mappers.
 *
 * <p>Entities map the domain model ({@link com.paymentgateway.settlement.domain.payment.Payment})
 * to PostgreSQL tables. Entities are one-way — they depend on domain objects
 * for mapping but never the reverse.</p>
 */
package com.paymentgateway.settlement.infrastructure.persistence.entity;
