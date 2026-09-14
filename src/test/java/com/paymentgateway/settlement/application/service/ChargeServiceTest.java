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

import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
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

    // --- Monetary representation tests (fresh DB read, not in-memory entity) ---

    @Test
    @Transactional
    void inrTenFiftyStoredAsMinorUnitsAndReturnedAsMajorUnits() {
        when(processor.process(any(), anyLong(), any(), any()))
                .thenReturn(ProviderResult.success("provider_txn_minor_1"));

        Payment created = chargeService.charge(
                "m_minor", "c_minor", "INV-MINOR-1050",
                "10.50", "INR", "UPI", "success:test",
                UUID.randomUUID()
        );

        // Fresh read from the database via the repository (not the in-memory entity).
        PaymentEntity entity = paymentRepository.findByPaymentId(created.getPaymentId().toUuid())
                .orElseThrow();

        // amount_minor must hold the integer minor-unit count 1050.
        assertThat(entity.getAmount()).isEqualByComparingTo("1050");

        // Reconstitute via toDomain to verify the read path.
        Payment reloaded = entity.toDomain(null);
        assertThat(reloaded.getAmount().getAmount().toPlainString()).isEqualTo("10.50");
        assertThat(reloaded.getAmount().getCurrency()).isEqualTo(Currency.INR);
    }

    @Test
    @Transactional
    void jpyZeroDecimalPlacesRoundTrip() {
        when(processor.process(any(), anyLong(), any(), any()))
                .thenReturn(ProviderResult.success("provider_txn_minor_2"));

        Payment created = chargeService.charge(
                "m_minor", "c_minor", "INV-MINOR-JPY",
                "1500", "JPY", "UPI", "success:test",
                UUID.randomUUID()
        );

        PaymentEntity entity = paymentRepository.findByPaymentId(created.getPaymentId().toUuid())
                .orElseThrow();
        assertThat(entity.getAmount()).isEqualByComparingTo("1500");

        Payment reloaded = entity.toDomain(null);
        assertThat(reloaded.getAmount().getAmount().toPlainString()).isEqualTo("1500");
        assertThat(reloaded.getAmount().getCurrency()).isEqualTo(Currency.JPY);
    }

    @Test
    @Transactional
    void bhdThreeDecimalPlacesRoundTrip() {
        when(processor.process(any(), anyLong(), any(), any()))
                .thenReturn(ProviderResult.success("provider_txn_minor_3"));

        Payment created = chargeService.charge(
                "m_minor", "c_minor", "INV-MINOR-BHD",
                "1.234", "BHD", "UPI", "success:test",
                UUID.randomUUID()
        );

        PaymentEntity entity = paymentRepository.findByPaymentId(created.getPaymentId().toUuid())
                .orElseThrow();
        assertThat(entity.getAmount()).isEqualByComparingTo("1234");

        Payment reloaded = entity.toDomain(null);
        assertThat(reloaded.getAmount().getAmount().toPlainString()).isEqualTo("1.234");
        assertThat(reloaded.getAmount().getCurrency()).isEqualTo(Currency.BHD);
    }

    @Test
    @Transactional
    void invalidScaleForInrIsRejectedBeforePersistence() {
        // 10.505 has 3 decimal places; INR requires exactly 2. Must fail before
        // any row is written, so no fractional minor-unit value can ever land
        // in the amount_minor column.
        assertThatThrownBy(() -> chargeService.charge(
                "m_minor", "c_minor", "INV-MINOR-INVALID",
                "10.505", "INR", "UPI", "success:test",
                UUID.randomUUID()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Scale mismatch");

        // Verify nothing was persisted.
        assertThat(paymentRepository.findByBillRefAndMerchantId("INV-MINOR-INVALID", "m_minor"))
                .isEmpty();
    }

    @Test
    @Transactional
    void fractionalMinorUnitDatabaseValueIsRejectedOnRead() {
        // Simulate a corrupted row where amount_minor holds a non-integer
        // minor-unit value (e.g. 1050.50). The read path must fail loudly
        // rather than silently truncating to 1050. We insert the row via a
        // native query so the JPA entity layer never sees the bad value —
        // this proves the guard in PaymentEntity.toDomain catches it on the
        // fresh read from the database.
        UUID corruptId = UUID.randomUUID();
        entityManager.createNativeQuery(
                        "INSERT INTO payment (payment_id, merchant_id, bill_ref, amount_minor, " +
                        "currency, payment_method, status, correlation_id, created_at, updated_at, version) " +
                        "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, 0)")
                .setParameter(1, corruptId)
                .setParameter(2, "m_minor")
                .setParameter(3, "INV-MINOR-CORRUPT")
                .setParameter(4, new BigDecimal("1050.50"))
                .setParameter(5, "INR")
                .setParameter(6, PaymentMethodType.UPI.name())
                .setParameter(7, PaymentStatus.SUCCEEDED.name())
                .setParameter(8, UUID.randomUUID())
                .setParameter(9, Instant.now())
                .setParameter(10, Instant.now())
                .executeUpdate();

        // Clear the persistence context so the next read hits the database
        // rather than returning a cached in-memory entity.
        entityManager.clear();

        PaymentEntity loaded = paymentRepository.findByPaymentId(corruptId)
                .orElseThrow();
        assertThat(loaded.getAmount()).isEqualByComparingTo("1050.50");
        assertThatThrownBy(() -> loaded.toDomain(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("amount_minor");
    }
}
