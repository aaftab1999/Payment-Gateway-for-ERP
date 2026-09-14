package com.paymentgateway.settlement.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Structured API error response.
 *
 * <p><strong>Security:</strong> never includes stack traces, internal
 * database messages, or sensitive data. The {@code correlationId} allows
 * the client to report an error and the operator to find the
 * corresponding log entry.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        long timestamp,
        int status,
        String error,
        String message,
        String path,
        List<ValidationError> details,
        String correlationId
) {
}
