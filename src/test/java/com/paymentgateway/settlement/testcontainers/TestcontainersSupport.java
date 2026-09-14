package com.paymentgateway.settlement.testcontainers;

/**
 * Marker interface / shared base for Testcontainers-based integration tests.
 *
 * <p>Concrete tests declare their own {@code @Container} fields with
 * {@code @ServiceConnection} so Spring Boot injects the correct
 * connection URLs automatically.</p>
 */
public interface TestcontainersSupport {
}
