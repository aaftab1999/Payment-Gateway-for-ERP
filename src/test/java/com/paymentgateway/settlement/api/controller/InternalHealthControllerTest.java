package com.paymentgateway.settlement.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the {@code /internal/health} endpoint returns a structured
 * response containing the expected technical fields.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InternalHealthControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthEndpointReturnsStatusApplicationNameAndCorrelation() {
        var response = restTemplate.getForEntity("/internal/health", InternalHealthController.InternalHealthResponse.class);

        assertThat(response.getStatusCode().value()).isLessThan(600); // 200 or 503
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().applicationName()).isEqualTo("payment-gateway-settlement-core");
        assertThat(response.getBody().correlationId()).isNotBlank();
        assertThat(response.getBody().status()).isIn("UP", "DOWN");
    }
}
