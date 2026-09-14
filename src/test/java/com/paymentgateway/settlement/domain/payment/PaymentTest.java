package com.paymentgateway.settlement.domain.payment;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    @Test
    void createPaymentInCreatedState() {
        Money amount = Money.of("1250.00", Currency.INR);
        UUID corrId = UUID.randomUUID();

        Payment payment = Payment.create(
                PaymentId.generate(),
                "m_123",
                "c_456",
                "INV-2024-001",
                amount,
                PaymentMethodType.UPI,
                "upi://pay/mock@upi",
                corrId
        );

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(payment.getPaymentId()).isNotNull();
        assertThat(payment.getAmount()).isEqualTo(amount);
        assertThat(payment.getCorrelationId()).isEqualTo(corrId);
        assertThat(payment.getCreatedAt()).isNotNull();
        assertThat(payment.getUpdatedAt()).isNotNull();
        assertThat(payment.getVersion()).isEqualTo(0L);
    }

    @Test
    void nullMerchantIdThrows() {
        Money amount = Money.of("100.00", Currency.INR);
        assertThatThrownBy(() -> Payment.create(
                PaymentId.generate(),
                null,
                "c_456",
                "INV-2024-001",
                amount,
                PaymentMethodType.UPI,
                "upi://pay/mock@upi",
                UUID.randomUUID()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("merchantId");
    }

    @Test
    void blankBillRefThrows() {
        Money amount = Money.of("100.00", Currency.INR);
        assertThatThrownBy(() -> Payment.create(
                PaymentId.generate(),
                "m_123",
                "c_456",
                "",
                amount,
                PaymentMethodType.UPI,
                "upi://pay/mock@upi",
                UUID.randomUUID()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("billRef");
    }

    @Test
    void nullPaymentTokenThrows() {
        Money amount = Money.of("100.00", Currency.INR);
        assertThatThrownBy(() -> Payment.create(
                PaymentId.generate(),
                "m_123",
                "c_456",
                "INV-001",
                amount,
                PaymentMethodType.UPI,
                null,
                UUID.randomUUID()
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void markProcessingTransitionsToProcessing() {
        Payment payment = createTestPayment();
        payment.markProcessing();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    void applyProviderResultSuccess() {
        Payment payment = createTestPayment();
        payment.markProcessing();

        ProviderResult result = ProviderResult.success("txn_123");
        payment.applyProviderResult(result);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getProviderReference()).isEqualTo("txn_123");
    }

    @Test
    void applyProviderResultDeclined() {
        Payment payment = createTestPayment();
        payment.markProcessing();

        payment.applyProviderResult(ProviderResult.declined("DECLINED", "Insufficient funds"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void applyProviderResultTechnicalFailure() {
        Payment payment = createTestPayment();
        payment.markProcessing();

        payment.applyProviderResult(ProviderResult.technicalFailure("HTTP_500", "Provider internal error"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void applyProviderResultUnknown() {
        Payment payment = createTestPayment();
        payment.markProcessing();

        payment.applyProviderResult(ProviderResult.unknown("TIMEOUT", "Provider timeout"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(payment.getFailureCode()).isEqualTo("TIMEOUT");
    }

    @Test
    void doubleApplyToTerminalFails() {
        Payment payment = createTestPayment();
        payment.markProcessing();
        payment.applyProviderResult(ProviderResult.success("txn_123"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        // Cannot apply another result to SUCCEEDED
        assertThatThrownBy(() -> payment.applyProviderResult(ProviderResult.declined("DECLINE", "too late")))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void resolveUnknownToSucceeded() {
        Payment payment = createTestPayment();
        payment.markProcessing();
        payment.applyProviderResult(ProviderResult.unknown("TIMEOUT", "timeout"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);

        payment.resolveReconciliation(PaymentStatus.SUCCEEDED, null, null, "txn_resolved_123");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getProviderReference()).isEqualTo("txn_resolved_123");
    }

    @Test
    void resolveUnknownToFailed() {
        Payment payment = createTestPayment();
        payment.markProcessing();
        payment.applyProviderResult(ProviderResult.unknown("TIMEOUT", "timeout"));

        payment.resolveReconciliation(PaymentStatus.FAILED, "CONFIRMED_FAIL", "Bank reported failure", null);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void resolveUnknownFromSucceededFails() {
        Payment payment = createTestPayment();
        payment.markProcessing();
        payment.applyProviderResult(ProviderResult.success("txn_123"));

        assertThatThrownBy(() -> payment.resolveReconciliation(PaymentStatus.FAILED, null, null, null))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    private static Payment createTestPayment() {
        return Payment.create(
                PaymentId.generate(),
                "m_test",
                "c_test",
                "INV-TEST-001",
                Money.of("100.00", Currency.INR),
                PaymentMethodType.UPI,
                "success:test",
                UUID.randomUUID()
        );
    }
}
