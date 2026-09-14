/*
 * Data Transfer Objects for the payment API.
 *
 * <p>Separate from the domain model to decouple the API contract from
 * persistence/interal representation. The {@code paymentToken} field is
 * <strong>never returned</strong> in any response.</p>
 */
package com.paymentgateway.settlement.api.dto;
