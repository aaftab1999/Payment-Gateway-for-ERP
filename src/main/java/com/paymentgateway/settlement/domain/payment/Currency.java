package com.paymentgateway.settlement.domain.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Currency with explicit scale (number of decimal places).
 *
 * <p>Scales are fixed per ISO-4217. INR = 2, JPY = 0, BHD = 3, etc.
 * The {@link #scale()} value is used by {@link Money} to convert
 * between decimal and minor-unit representations.</p>
 */
public enum Currency {
    INR("Indian Rupee", 2),
    USD("US Dollar", 2),
    EUR("Euro", 2),
    GBP("Pound Sterling", 2),
    JPY("Japanese Yen", 0),
    AED("UAE Dirham", 2),
    BHD("Bahraini Dinar", 3),
    KWD("Kuwaiti Dinar", 3),
    JOD("Jordanian Dinar", 3),
    OMR("Omani Rial", 3);

    private final String displayName;
    private final int scale;

    Currency(final String displayName, final int scale) {
        this.displayName = displayName;
        this.scale = scale;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getScale() {
        return scale;
    }

    public static Currency fromCode(final String code) {
        try {
            return Currency.valueOf(code.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported currency: " + code);
        }
    }

    public boolean isSupported() {
        return true;
    }
}
