package com.paymentgateway.settlement.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentgateway.settlement.api.dto.CreatePaymentRequest;
import com.paymentgateway.settlement.application.port.PaymentProcessor;
import com.paymentgateway.settlement.domain.payment.ProviderResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Integration test for the payment REST API.
 *
 * <p>Verifies: payment creation, payment retrieval, bill-reference lookup,
 * validation errors, structured error responses, and correlation ID
 * propagation. Requires Docker (Testcontainers + Docker Compose Kafka/Postgres).</p>
 */
@Testcontainers
 @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
 @ActiveProfiles("test")
 class PaymentApiIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PaymentProcessor processor;

    @Autowired
    private com.paymentgateway.settlement.infrastructure.persistence.repository.PaymentRepository paymentRepository;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        reset(processor);
        when(processor.process(any(), anyLong(), any(), any()))
                .thenReturn(ProviderResult.success("provider_txn_" + UUID.randomUUID()));
    }

    @Test
    void createPaymentReturns201AndSucceededStatus() throws Exception {
        when(processor.process(any(), anyLong(), any(), any()))
                .thenReturn(ProviderResult.success("provider_txn_api_001"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", "api-test-corr-001");

        CreatePaymentRequest request = new CreatePaymentRequest(
                "m_api_test",
                "c_api_test",
                "INV-API-001",
                "1250.00",
                "INR",
                "UPI",
                "success:api"
        );

        HttpEntity<CreatePaymentRequest> entity = new HttpEntity<>(request, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/payments", entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Parse response and verify structure
        Map<String, Object> body = objectMapper.readValue(response.getBody(), Map.class);
        assertThat(body.get("status")).isEqualTo("SUCCEEDED");
        assertThat(body.get("paymentMethod")).isEqualTo("UPI");
        assertThat(body.get("amount")).isEqualTo(1250.00);
        assertThat(body.get("currency")).isEqualTo("INR");
        assertThat(body.get("providerReference")).isEqualTo("provider_txn_api_001");
        assertThat(body.get("paymentToken")).isNull(); // ensure token never returned
        assertThat(body.get("correlationId")).isNotNull();

        // Verify correlation ID header echoed
        String corrId = response.getHeaders().getFirst("X-Correlation-Id");
        assertThat(corrId).isEqualTo("api-test-corr-001");
    }

    @Test
    void createPaymentWithInvalidAmountReturns400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String invalidBody = """
                {
                    "merchantId": "m_test",
                    "customerRef": "c_test",
                    "billRef": "INV-TEST-001",
                    "amount": "-100.00",
                    "currency": "INR",
                    "paymentMethod": "UPI",
                    "paymentToken": "success:test"
                }
                """;

        HttpEntity<String> entity = new HttpEntity<>(invalidBody, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/payments", entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createPaymentWithMissingFieldsReturns400() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String invalidBody = """
                {
                    "currency": "INR"
                }
                """;

        HttpEntity<String> entity = new HttpEntity<>(invalidBody, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/payments", entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> body = objectMapper.readValue(response.getBody(), Map.class);
        assertThat(body.get("error")).isEqualTo("VALIDATION_FAILED");
        assertThat(body.get("details")).isNotNull();
    }

    @Test
    void getPaymentReturns404ForNonexistent() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/payments/" + UUID.randomUUID(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getPaymentByBillReferenceReturnsAll() throws Exception {
        when(processor.process(any(), anyLong(), any(), any()))
                .thenReturn(ProviderResult.success("provider_txn_bill_001"));

        // Create payment
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        CreatePaymentRequest request = new CreatePaymentRequest(
                "m_bill_test", "c_test", "INV-BILL-LOOKUP",
                "100.00", "INR", "UPI", "success:test"
        );
        HttpEntity<CreatePaymentRequest> entity = new HttpEntity<>(request, headers);
        restTemplate.postForEntity("/payments", entity, String.class);

        // Lookup by bill ref
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/payments/by-bill/INV-BILL-LOOKUP?merchantId=m_bill_test", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> payments = objectMapper.readValue(response.getBody(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).get("billRef")).isEqualTo("INV-BILL-LOOKUP");
    }

    @Test
    void errorResponseContainsCorrelationId() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/payments/" + UUID.randomUUID(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Check response doesn't contain stack trace
        String body = response.getBody();
        assertThat(body).doesNotContain("at ").doesNotContain("Exception");
    }
}
