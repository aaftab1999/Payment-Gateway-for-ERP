package com.paymentgateway.settlement.api.dto;

/**
 * A single validation error field.
 */
public record ValidationError(
        String field,
        String message) {
}
