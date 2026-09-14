/*
 * Cross-cutting framework wiring: data sources, Kafka, Flyway,
 * Redis and Micrometer are auto-configured by Spring Boot starters.
 *
 * <p>This package collects explicit bean definitions that are not covered
 * by convention-based auto-configuration, e.g. correlation-id filters,
 * custom deserializers and outbox schedulers (added in later stages).</p>
 */
package com.paymentgateway.settlement.config;
