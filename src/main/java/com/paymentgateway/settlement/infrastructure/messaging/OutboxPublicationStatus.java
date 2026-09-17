package com.paymentgateway.settlement.infrastructure.messaging;

public enum OutboxPublicationStatus {
    PENDING,
    PUBLISHED,
    FAILED,
    DEAD_LETTERED
}
