/*
 * Payment domain package.
 *
 * <p>Contains the Payment aggregate root, value objects (Money, Currency,
 * PaymentId, PaymentMethodType), the state machine (PaymentStateEngine,
 * PaymentStatus), and ProviderResult.</p>
 *
 * <p><strong>Ownership boundary:</strong> The Payment aggregate owns
 * payment processing state (status, provider reference, amounts). It does
 * NOT own ERP invoice balances, customer details beyond references,
 * or bill amounts — those live in the ERP.</p>
 *
 * <p><strong>Ledger integration (Stage 4 placeholder):</strong>
 * The Payment aggregate exposes {@code ledgerEntryId} as a nullable field
 * that will be populated when the ledger service posts entries. The workflow
 * is designed so ledger posting happens in the same transaction as the
 * status transition (TX2 in the architecture), ensuring atomicity.</p>
 */
package com.paymentgateway.settlement.domain.payment;
