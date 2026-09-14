package com.paymentgateway.settlement.application.service;

import com.paymentgateway.settlement.application.port.PaymentProcessor;
import com.paymentgateway.settlement.domain.payment.*;
import com.paymentgateway.settlement.infrastructure.persistence.entity.PaymentEntity;
import com.paymentgateway.settlement.infrastructure.persistence.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Service-layer integration tests for {@link ChargeService}.
 *
 * <p>Requires Docker (Testcontainers PostgreSQL). The test starts a
 * PostgreSQL container via {@code @ServiceConnection} and runs real
 * JPA transactions.</p>
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ChargeServiceTest {

    @Autowired
    private ChargeService chargeService;

    @Autowired
    private PaymentRepository paymentRepository;

    @MockBean
    private PaymentProcessor processor;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        when(processor.process(any(), anyLong(), any(), any()))
                .thenReturn(ProviderResult.success("provider_txn_123"));
    }

    @Test
    @Transactional
    void chargeCreatesAndSucceeds() {
        UUID correlationId = UUID.randomUUID();

        Payment payment = chargeService.charge(
                "m_test",
                "c_test",
                "INV-TEST-001",
                "1250.00",
                "INR",
                "UPI",
                "success:test",
                correlationId
        );

        // Verify the payment is persisted and in SUCCEEDED state
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getProviderReference()).isEqualTo("provider_txn_123");

        // Verify it's in the database
        PaymentEntity entity = paymentRepository.findByPaymentId(payment.getPaymentId().toUuid()).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(entity.getAmount()).isEqualByComparingTo("125000.00");
    }

    @Test
    @Transactional
    void chargeDeclinedPaymentFails() {
        when(processor.process(any(), anyLong(), any(), any()))
                .thenReturn(ProviderResult.declined("DECLINED", "Insufficient funds"));

        Payment payment = chargeService.charge(
                "m_test", "c_test", "INV-TEST-002",
                "500.00", "INR", "UPI", "decline:test",
                UUID.randomUUID()
        );

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isEqualTo("DECLINED");
    }

    @Test
    @Transactional
    void chargeTimeoutResultsInUnknown() {
        when(processor.process(any(), anyLong(), any(), any()))
                .thenReturn(ProviderResult.unknown("TIMEOUT", "Provider timeout"));

        Payment payment = chargeService.charge(
                "m_test", "c_test", "INV-TEST-003",
                "750.00", "INR", "UPI", "timeout:test",
                UUID.randomUUID()
        );

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(payment.getFailureCode()).isEqualTo("TIMEOUT");
    }

    @Test
    @Transactional
    void getPaymentDoesNotReturnToken() {
        when(processor.process(any(), anyLong(), any(), any()))
                .thenReturn(ProviderResult.success("provider_txn_456"));

        Payment created = chargeService.charge(
                "m_test", "c_test", "INV-TEST-004",
                "100.00", "INR", "UPI", "success:test",
                UUID.randomUUID()
        );

        Payment retrieved = chargeService.getPayment(created.getPaymentId().toUuid());
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getPaymentId()).isEqualTo(created.getPaymentId());
        assertThat(retrieved.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        // Token must not be reconstructable from persistence (it's @Transient)
        // The retrieved payment's token will be null since it's never persisted
        assertThat(retrieved.getPaymentToken()).isNull();
    }

    @Test
    @Transactional
    void getPaymentNotFoundThrows() {
        assertThatThrownBy(() -> chargeService.getPayment(UUID.randomUUID()))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
    }

     @Test
    @Transactional
    void getPaymentsByBillRefReturnsAllForBill() {
        when(processor.process(any(), anyLong(), any(), any()))
                .thenReturn(ProviderResult.success("provider_txn_bulk_1"))
                .thenReturn(ProviderResult.success("provider_txn_bulk_2"));

        // Create 2 payments for the same bill
        chargeService.charge("m_test", "c_test", "INV-BULK-001",
                "100.00", "INR", "UPI", "success:test", UUID.randomUUID());
        chargeService.charge("m_test", "c_test", "INV-BULK-001",
                "200.00", "INR", "UPI", "success:test2", UUID.randomUUID());

        var payments = chargeService.getPaymentsByBillRef("m_test", "INV-BULK-001");

        assertThat(payments).hasSize(2);
        assertThat(payments).allSatisfy(p ->
                assertThat(p.getBillRef()).isEqualTo("INV-BULK-001"));
    }

    @Test
    @Transactional
    void chargeWithInvalidCurrencyThrows() {
        assertThatThrownBy(() -> chargeService.charge(
                "m_test", "c_test", "INV-TEST-XX",
                "100.00", "XYZ", "UPI", "success:test",
                UUID.randomUUID()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported currency");
    }

    @Test
    @Transactional
    void chargeWithNegativeAmountThrows() {
        assertThatThrownBy(() -> chargeService.charge(
                "m_test", "c_test", "INV-TEST-XX",
                "-50.00", "INR", "UPI", "success:test",
                UUID.randomUUID()
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
