/*
 * Observability foundation: correlation-id propagation (MDC),
 * structured request logging, and health/metrics plumbing.
 *
 * <p>No business endpoints expose payment data here; all payment
 * MDC fields (paymentId, merchantId) are populated later in the
 * charge controller.</p>
 */
package com.paymentgateway.settlement.observability;
