package com.paymentgateway.settlement.domain.payment;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Immutable monetary value object.
 *
 * <p><strong>Design decisions:</strong></p>
 * <ul>
 *   <li>Amounts are stored as {@code BigDecimal} for type safety and
 *       explicit scale control at the domain level.</li>
 *   <li>Amounts must be non-negative at construction; negative amounts
 *       are rejected — refunds and reversals are separate payment records,
 *       not signed amounts.</li>
 *   <li>Scale is validated against the currency to prevent mismatched
 *       precision (e.g. 10.001 is rejected for INR which has scale 2).</li>
 *   <li>{@link #toMinorUnits()} and {@link #fromMinorUnits(long, Currency)}
 *       provide interoperability with the database column (DECIMAL(18,2)).</li>
 * </ul>
 *
 * <p><strong>Why not {@code double}/{@code float}?</strong>
 * Binary floating-point cannot represent decimal fractions exactly
 * (e.g. 0.1 in double is 0.1000000000000000055511151231257827021181583404541015625).
 * For financial systems this causes cumulative rounding errors.
 * BigDecimal provides exact decimal arithmetic.</p>
 */
public final class Money {

    private final BigDecimal amount;
    private final Currency currency;

    private Money(final BigDecimal amount, final Currency currency) {
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.currency = Objects.requireNonNull(currency, "currency must not be null");
    }

    /**
     * Creates a Money from a BigDecimal. The amount's scale must match
     * the currency's scale — no implicit rounding.
     */
    public static Money of(final BigDecimal amount, final Currency currency) {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Amount must be non-negative: " + amount);
        }
        if (amount.scale() != currency.getScale()) {
            throw new IllegalArgumentException(
                    "Scale mismatch: amount scale " + amount.scale() +
                            " does not match currency " + currency.name() +
                            " scale " + currency.getScale()
            );
        }
        return new Money(amount, currency);
    }

    public static Money of(final String amount, final Currency currency) {
        return of(new BigDecimal(amount), currency);
    }

    public static Money zero(final Currency currency) {
        return of(BigDecimal.ZERO.setScale(currency.getScale()), currency);
    }

    public Money add(final Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException("Cannot add different currencies: " + this.currency + " vs " + other.currency);
        }
        return new Money(this.amount.add(other.amount), currency);
    }

    public Money subtract(final Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException("Cannot subtract different currencies: " + this.currency + " vs " + other.currency);
        }
        BigDecimal result = this.amount.subtract(other.amount);
        if (result.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Subtraction would produce negative amount: " + result);
        }
        return new Money(result, currency);
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Currency getCurrency() {
        return currency;
    }

    /**
     * Converts to minor units (e.g. paise/cents) for database storage.
     * Uses longValueExact to detect overflow.
     */
    public long toMinorUnits() {
        return amount.movePointRight(currency.getScale()).longValueExact();
    }

    public static Money fromMinorUnits(final long minorUnits, final Currency currency) {
        BigDecimal bd = BigDecimal.valueOf(minorUnits, currency.getScale());
        return new Money(bd, currency);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof Money money)) return false;
        return amount.compareTo(money.amount) == 0 && currency == money.currency;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO : amount, currency);
    }

    @Override
    public String toString() {
        return currency.name() + " " + amount.toPlainString();
    }
}
