package com.paymentgateway.settlement.domain.payment;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentStateEngineTest {

    // --- Valid transitions ---

    @Test
    void createdToProcessingIsValid() {
        PaymentStatus result = PaymentStateEngine.transition(
                PaymentStatus.CREATED, PaymentStatus.PROCESSING,
                PaymentStateEngine.TransitionReason.SUBMITTED_TO_PROVIDER);
        assertThat(result).isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    void processingToSucceededIsValid() {
        PaymentStatus result = PaymentStateEngine.transition(
                PaymentStatus.PROCESSING, PaymentStatus.SUCCEEDED,
                PaymentStateEngine.TransitionReason.PROVIDER_SUCCESS);
        assertThat(result).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void processingToFailedIsValid() {
        PaymentStatus result = PaymentStateEngine.transition(
                PaymentStatus.PROCESSING, PaymentStatus.FAILED,
                PaymentStateEngine.TransitionReason.PROVIDER_DECLINED);
        assertThat(result).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void processingToUnknownIsValid() {
        PaymentStatus result = PaymentStateEngine.transition(
                PaymentStatus.PROCESSING, PaymentStatus.UNKNOWN,
                PaymentStateEngine.TransitionReason.PROVIDER_UNKNOWN_OUTCOME);
        assertThat(result).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    void processingToRequiresReconciliationIsValid() {
        PaymentStatus result = PaymentStateEngine.transition(
                PaymentStatus.PROCESSING, PaymentStatus.REQUIRES_RECONCILIATION,
                PaymentStateEngine.TransitionReason.PROVIDER_TECHNICAL_FAILURE);
        assertThat(result).isEqualTo(PaymentStatus.REQUIRES_RECONCILIATION);
    }

    @Test
    void unknownToSucceededIsValid() {
        PaymentStatus result = PaymentStateEngine.transition(
                PaymentStatus.UNKNOWN, PaymentStatus.SUCCEEDED,
                PaymentStateEngine.TransitionReason.RECONCILIATION_MATCHED);
        assertThat(result).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void unknownToFailedIsValid() {
        PaymentStatus result = PaymentStateEngine.transition(
                PaymentStatus.UNKNOWN, PaymentStatus.FAILED,
                PaymentStateEngine.TransitionReason.RECONCILIATION_FAILED);
        assertThat(result).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void requiresReconciliationToSucceededIsValid() {
        PaymentStatus result = PaymentStateEngine.transition(
                PaymentStatus.REQUIRES_RECONCILIATION, PaymentStatus.SUCCEEDED,
                PaymentStateEngine.TransitionReason.RECONCILIATION_MATCHED);
        assertThat(result).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void requiresReconciliationToFailedIsValid() {
        PaymentStatus result = PaymentStateEngine.transition(
                PaymentStatus.REQUIRES_RECONCILIATION, PaymentStatus.FAILED,
                PaymentStateEngine.TransitionReason.RECONCILIATION_FAILED);
        assertThat(result).isEqualTo(PaymentStatus.FAILED);
    }

    // --- Invalid transitions ---

    @Test
    void createdToSucceededIsInvalid() {
        assertThatThrownBy(() -> PaymentStateEngine.transition(
                PaymentStatus.CREATED, PaymentStatus.SUCCEEDED,
                PaymentStateEngine.TransitionReason.PROVIDER_SUCCESS))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void succeededToFailedIsInvalid() {
        assertThatThrownBy(() -> PaymentStateEngine.transition(
                PaymentStatus.SUCCEEDED, PaymentStatus.FAILED,
                PaymentStateEngine.TransitionReason.PROVIDER_DECLINED))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("terminal");
    }

    @Test
    void processingToProcessingIsInvalid() {
        assertThatThrownBy(() -> PaymentStateEngine.transition(
                PaymentStatus.PROCESSING, PaymentStatus.PROCESSING,
                PaymentStateEngine.TransitionReason.SUBMITTED_TO_PROVIDER))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("already in status");
    }

    @Test
    void processingToCreatedIsInvalid() {
        assertThatThrownBy(() -> PaymentStateEngine.transition(
                PaymentStatus.PROCESSING, PaymentStatus.CREATED,
                PaymentStateEngine.TransitionReason.SUBMITTED_TO_PROVIDER))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void requiresReconciliationToUnknownIsInvalid() {
        // REQUIRES_RECONCILIATION can only resolve to a terminal state, not back
        // to another awaiting-resolution state.
        assertThatThrownBy(() -> PaymentStateEngine.transition(
                PaymentStatus.REQUIRES_RECONCILIATION, PaymentStatus.UNKNOWN,
                PaymentStateEngine.TransitionReason.PROVIDER_UNKNOWN_OUTCOME))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void nullCurrentStatusThrows() {
        assertThatThrownBy(() -> PaymentStateEngine.transition(
                null, PaymentStatus.PROCESSING,
                PaymentStateEngine.TransitionReason.SUBMITTED_TO_PROVIDER))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void terminalStatusIsCorrect() {
        assertThat(PaymentStatus.SUCCEEDED.isTerminal()).isTrue();
        assertThat(PaymentStatus.FAILED.isTerminal()).isTrue();
        assertThat(PaymentStatus.CREATED.isTerminal()).isFalse();
        assertThat(PaymentStatus.PROCESSING.isTerminal()).isFalse();
        assertThat(PaymentStatus.UNKNOWN.isTerminal()).isFalse();
        assertThat(PaymentStatus.REQUIRES_RECONCILIATION.isTerminal()).isFalse();
    }

    @Test
    void voidedIsTerminalAndHasNoOutgoingTransitions() {
        // VOIDED is a future state (Stage 4+). It is terminal today and must
        // not be reachable from any Stage 3 state.
        assertThat(PaymentStatus.VOIDED.isTerminal()).isTrue();
        assertThatThrownBy(() -> PaymentStateEngine.transition(
                PaymentStatus.SUCCEEDED, PaymentStatus.VOIDED,
                PaymentStateEngine.TransitionReason.RECONCILIATION_MATCHED))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void refundedIsTerminalAndHasNoOutgoingTransitions() {
        // REFUNDED is a future state (Stage 4+). It is terminal today and must
        // not be reachable from any Stage 3 state.
        assertThat(PaymentStatus.REFUNDED.isTerminal()).isTrue();
        assertThatThrownBy(() -> PaymentStateEngine.transition(
                PaymentStatus.SUCCEEDED, PaymentStatus.REFUNDED,
                PaymentStateEngine.TransitionReason.RECONCILIATION_MATCHED))
                .isInstanceOf(IllegalStateTransitionException.class);
    }
}
