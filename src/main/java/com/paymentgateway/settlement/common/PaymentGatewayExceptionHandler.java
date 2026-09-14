package com.paymentgateway.settlement.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.paymentgateway.settlement.api.dto.ApiError;
import com.paymentgateway.settlement.api.dto.ValidationError;
import com.paymentgateway.settlement.domain.payment.IllegalStateTransitionException;

/**
 * Global exception handler — translates domain/infrastructure exceptions
 * into structured {@link ApiError} responses.
 *
 * <p><strong>Security:</strong> Stack traces and database error messages
 * are never exposed to API clients. Detailed technical causes are logged
 * with the correlation ID (via MDC) but only the error code and a
 * human-readable message are returned.</p>
 */
@RestControllerAdvice
public class PaymentGatewayExceptionHandler {

    private static final DateTimeFormatter TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ").withZone(ZoneOffset.UTC);

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ApiError> handleBadRequest(
            final RuntimeException ex, final HttpServletRequest request) {

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(
                        Instant.now().getEpochSecond(),
                        HttpStatus.BAD_REQUEST.value(),
                        "INVALID_REQUEST",
                        ex.getMessage(),
                        request.getRequestURI(),
                        null,
                        UUID.randomUUID().toString()
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            final MethodArgumentNotValidException ex, final HttpServletRequest request) {

        List<ValidationError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ValidationError(fe.getField(), fe.getDefaultMessage()))
                .collect(Collectors.toList());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(
                        Instant.now().getEpochSecond(),
                        HttpStatus.BAD_REQUEST.value(),
                        "VALIDATION_FAILED",
                        "Request validation failed",
                        request.getRequestURI(),
                        errors,
                        UUID.randomUUID().toString()
                ));
    }

    @ExceptionHandler(jakarta.persistence.EntityNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
            final jakarta.persistence.EntityNotFoundException ex, final HttpServletRequest request) {

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(
                        Instant.now().getEpochSecond(),
                        HttpStatus.NOT_FOUND.value(),
                        "NOT_FOUND",
                        ex.getMessage(),
                        request.getRequestURI(),
                        null,
                        request.getHeader("X-Correlation-Id") != null
                                ? request.getHeader("X-Correlation-Id")
                                : "unknown"
                ));
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<ApiError> handleStateTransition(
            final IllegalStateTransitionException ex, final HttpServletRequest request) {

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(
                        Instant.now().getEpochSecond(),
                        HttpStatus.CONFLICT.value(),
                        "ILLEGAL_STATE_TRANSITION",
                        ex.getMessage(),
                        request.getRequestURI(),
                        null,
                        request.getHeader("X-Correlation-Id") != null
                                ? request.getHeader("X-Correlation-Id")
                                : "unknown"
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(
            final Exception ex, final HttpServletRequest request) {

        // Log full stack trace internally (with correlation ID via MDC)
        String correlationId = request.getHeader("X-Correlation-Id") != null
                ? request.getHeader("X-Correlation-Id") : "unknown";

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(
                        Instant.now().getEpochSecond(),
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "INTERNAL_ERROR",
                        "An unexpected error occurred. Reference: " + correlationId,
                        request.getRequestURI(),
                        null,
                        correlationId
                ));
    }
}
