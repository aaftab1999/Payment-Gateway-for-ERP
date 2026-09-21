package com.paymentgateway.settlement.infrastructure.external.provider.razorpay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentgateway.settlement.config.RazorpayProperties;
import com.paymentgateway.settlement.domain.payment.ProviderResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class RazorpayPaymentProcessorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID correlationId = UUID.randomUUID();
    private final String providerKey = "prov_" + UUID.randomUUID();

    private static String orderJson() {
        return """
                {
                  "id": "order_DaZlswtdcn9UNV",
                  "entity": "order",
                  "amount": 125000,
                  "amount_paid": 0,
                  "amount_due": 125000,
                  "currency": "INR",
                  "receipt": "test-receipt",
                  "status": "created",
                  "attempts": 0,
                  "notes": {"correlationId": "abc-123"},
                  "created_at": 1726886400
                }
                """;
    }

    private static String authHeader() {
        return "Basic dGVzdF9rZXk6dGVzdF9zZWNyZXQ=";
    }

    private RazorpayPaymentProcessor buildProcessorWithServer(
            MockRestServiceServer[] holder) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.razorpay.com/v1")
                .defaultHeader("Authorization", authHeader());

        holder[0] = MockRestServiceServer.bindTo(builder).build();
        return new RazorpayPaymentProcessor(
                builder.build(),
                new RazorpayProperties(),
                objectMapper);
    }

    @Test
    void createsOrderAndReturnsUnknownWithOrderId() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        RazorpayPaymentProcessor processor = buildProcessorWithServer(holder);

        holder[0].expect(once(), requestTo("https://api.razorpay.com/v1/orders"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(orderJson(), MediaType.APPLICATION_JSON));

        ProviderResult result = processor.process(
                "tok_razorpay", 125000L, "INR",
                correlationId, providerKey);

        assertThat(result.type()).isEqualTo(ProviderResult.Type.UNKNOWN);
        assertThat(result.providerReference()).isEqualTo("order_DaZlswtdcn9UNV");
        assertThat(result.failureCode()).isEqualTo("ORDER_CREATED");
        assertThat(result.failureReason()).contains("awaiting customer payment");

        holder[0].verify();
    }

    @Test
    void providerIdempotencyKeyIsMappedToReceipt() {
        String key = "prov_" + UUID.randomUUID();
        String expectedReceipt = key.substring("prov_".length());

        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        RazorpayPaymentProcessor processor = buildProcessorWithServer(holder);

        holder[0].expect(requestTo("https://api.razorpay.com/v1/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("\"receipt\":\"" + expectedReceipt + "\"")))
                .andRespond(withSuccess(orderJson(), MediaType.APPLICATION_JSON));

        processor.process("tok_razorpay", 100L, "INR",
                UUID.randomUUID(), key);

        holder[0].verify();
    }

    @Test
    void emptyResponseReturnsUnknown() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        RazorpayPaymentProcessor processor = buildProcessorWithServer(holder);

        holder[0].expect(requestTo("https://api.razorpay.com/v1/orders"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        ProviderResult result = processor.process(
                "tok_razorpay", 100L, "INR",
                UUID.randomUUID(), providerKey);

        assertThat(result.type()).isEqualTo(ProviderResult.Type.UNKNOWN);
        assertThat(result.failureCode()).isEqualTo("EMPTY_RESPONSE");

        holder[0].verify();
    }

    @Test
    void http500ReturnsTechnicalFailure() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        RazorpayPaymentProcessor processor = buildProcessorWithServer(holder);

        holder[0].expect(requestTo("https://api.razorpay.com/v1/orders"))
                .andRespond(withServerError().body("{\"error\":\"internal\"}"));

        ProviderResult result = processor.process(
                "tok_razorpay", 100L, "INR",
                UUID.randomUUID(), providerKey);

        assertThat(result.type()).isEqualTo(ProviderResult.Type.TECHNICAL_FAILURE);
        assertThat(result.failureCode()).isEqualTo("RAZORPAY_ERROR");
        assertThat(result.failureReason()).contains("500");

        holder[0].verify();
    }

    @Test
    void http401ReturnsTechnicalFailureAuthError() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        RazorpayPaymentProcessor processor = buildProcessorWithServer(holder);

        holder[0].expect(requestTo("https://api.razorpay.com/v1/orders"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));

        ProviderResult result = processor.process(
                "tok_razorpay", 100L, "INR",
                UUID.randomUUID(), providerKey);

        assertThat(result.type()).isEqualTo(ProviderResult.Type.TECHNICAL_FAILURE);
        assertThat(result.failureCode()).isEqualTo("AUTH_ERROR");

        holder[0].verify();
    }

    @Test
    void http400ReturnsDeclined() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        RazorpayPaymentProcessor processor = buildProcessorWithServer(holder);

        holder[0].expect(requestTo("https://api.razorpay.com/v1/orders"))
                .andRespond(withBadRequest().body("{\"error\":\"bad\"}"));

        ProviderResult result = processor.process(
                "tok_razorpay", 100L, "INR",
                UUID.randomUUID(), providerKey);

        assertThat(result.type()).isEqualTo(ProviderResult.Type.DECLINED);
        assertThat(result.failureCode()).isEqualTo("RAZORPAY_REJECTED");
        assertThat(result.isConfirmedFailure()).isTrue();

        holder[0].verify();
    }
}
