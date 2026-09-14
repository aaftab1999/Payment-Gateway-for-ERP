package com.paymentgateway.settlement.infrastructure.external.provider;

import com.paymentgateway.settlement.domain.payment.ProviderResult;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedPaymentProcessorTest {

    private final SimulatedPaymentProcessor processor = new SimulatedPaymentProcessor();

    private final UUID correlationId = UUID.randomUUID();

    @Test
    void successScenarioReturnsSuccessWithProviderRef() {
        ProviderResult result = processor.process("success:payment", 125000L, "INR", correlationId, "prov_test_1");
        assertThat(result.type()).isEqualTo(ProviderResult.Type.SUCCESS);
        assertThat(result.providerReference()).startsWith("provider_txn_");
        assertThat(result.isConfirmedSuccess()).isTrue();
    }

    @Test
    void declineScenarioReturnsDeclined() {
        ProviderResult result = processor.process("decline:bad_funds", 10000L, "INR", correlationId, "prov_test_2");
        assertThat(result.type()).isEqualTo(ProviderResult.Type.DECLINED);
        assertThat(result.isConfirmedFailure()).isTrue();
        assertThat(result.failureCode()).isEqualTo("DECLINED");
    }

    @Test
    void timeoutScenarioReturnsUnknown() {
        ProviderResult result = processor.process("timeout:slow", 5000L, "INR", correlationId, "prov_test_3");
        assertThat(result.type()).isEqualTo(ProviderResult.Type.UNKNOWN);
        assertThat(result.isAmbiguous()).isTrue();
        assertThat(result.isConfirmedFailure()).isFalse();
    }

    @Test
    void error500ScenarioReturnsTechnicalFailure() {
        ProviderResult result = processor.process("error500:crashed", 3000L, "INR", correlationId, "prov_test_4");
        assertThat(result.type()).isEqualTo(ProviderResult.Type.TECHNICAL_FAILURE);
        assertThat(result.isConfirmedFailure()).isTrue();
        assertThat(result.failureCode()).isEqualTo("PROVIDER_ERROR");
    }

    @Test
    void connfailScenarioReturnsTechnicalFailure() {
        ProviderResult result = processor.process("connfail:norefused", 2000L, "INR", correlationId, "prov_test_5");
        assertThat(result.type()).isEqualTo(ProviderResult.Type.TECHNICAL_FAILURE);
        assertThat(result.failureCode()).isEqualTo("CONNECTION_ERROR");
    }

    @Test
    void unknownScenarioReturnsUnknown() {
        ProviderResult result = processor.process("unknown:ambig", 1000L, "INR", correlationId, "prov_test_6");
        assertThat(result.type()).isEqualTo(ProviderResult.Type.UNKNOWN);
        assertThat(result.isAmbiguous()).isTrue();
    }

    @Test
    void unrecognizedTokenDefaultsToSuccess() {
        ProviderResult result = processor.process("some-random-token", 9999L, "INR", correlationId, "prov_test_7");
        assertThat(result.type()).isEqualTo(ProviderResult.Type.SUCCESS);
        assertThat(result.providerReference()).startsWith("provider_txn_");
    }

    @Test
    void nullTokenReturnsSuccess() {
        ProviderResult result = processor.process(null, 100L, "INR", correlationId, "prov_test_8");
        assertThat(result.type()).isEqualTo(ProviderResult.Type.SUCCESS);
    }

    @Test
    void providerIdempotencyKeyDeduplicatesCalls() {
        String key = "prov_idem_test";
        ProviderResult first = processor.process("success:payment", 100L, "INR", correlationId, key);
        ProviderResult second = processor.process("decline:bad_funds", 100L, "INR", correlationId, key);
        // Second call should return the same result as the first (idempotent).
        assertThat(second.type()).isEqualTo(first.type());
        assertThat(second.providerReference()).isEqualTo(first.providerReference());
    }
}
