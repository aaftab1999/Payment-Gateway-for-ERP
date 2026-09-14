package com.paymentgateway.settlement;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test: verifies that the Spring application context starts
 * successfully.
 *
 * <p>This test deliberately disables infrastructure auto-configuration
 * (datasource, Redis, Kafka) so it runs without Docker. Integration
 * tests that need real services live in {@code src/test/integration}.</p>
 */
@SpringBootTest(
        properties = {
                "spring.autoconfigure.exclude=" +
                        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration," +
                "org.flywaydb.core.api.configuration.FlywayConfigurationAutoConfiguration"
        }
)
@ActiveProfiles("test")
class ApplicationContextTest {

    @Test
    void contextLoads() {
        // If this passes, all configuration classes, filters, and beans
        // wire up correctly without errors.
    }

    @TestConfiguration
    static class NoInfrastructureConfig {
        // Placeholder for any test-only beans needed in future stages.
    }

    @MockBean
    private com.paymentgateway.settlement.infrastructure.persistence.repository.PaymentRepository paymentRepository;

    @MockBean
    private com.paymentgateway.settlement.application.port.PaymentProcessor processor;
}
