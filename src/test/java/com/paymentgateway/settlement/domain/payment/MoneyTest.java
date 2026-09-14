package com.paymentgateway.settlement.domain.payment;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void createsMoneyWithCorrectScale() {
        Money money = Money.of("1250.00", Currency.INR);
        assertThat(money.getAmount()).isEqualByComparingTo("1250.00");
        assertThat(money.getCurrency()).isEqualTo(Currency.INR);
    }

    @Test
    void rejectsNegativeAmount() {
        assertThatThrownBy(() -> Money.of("-100.00", Currency.INR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    @Test
    void rejectsWrongScale() {
        assertThatThrownBy(() -> Money.of("1250.001", Currency.INR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Scale mismatch");
    }

    @Test
    void toMinorUnitsAndFromMinorUnitsRoundtrip() {
        Money original = Money.of("1250.50", Currency.INR);
        long minor = original.toMinorUnits();
        assertThat(minor).isEqualTo(125050);
        Money restored = Money.fromMinorUnits(minor, Currency.INR);
        assertThat(restored).isEqualTo(original);
    }

    @Test
    void addSameCurrencySucceeds() {
        Money a = Money.of("100.00", Currency.INR);
        Money b = Money.of("50.00", Currency.INR);
        assertThat(a.add(b).getAmount()).isEqualByComparingTo("150.00");
    }

    @Test
    void addDifferentCurrencyThrows() {
        Money a = Money.of("100.00", Currency.INR);
        Money b = Money.of("50.00", Currency.USD);
        assertThatThrownBy(() -> a.add(b))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different currencies");
    }

    @Test
    void jpyHasZeroScale() {
        Money money = Money.of("1000", Currency.JPY);
        assertThat(money.getAmount()).isEqualByComparingTo("1000");
        assertThat(money.toMinorUnits()).isEqualTo(1000);
    }

    // --- Monetary representation tests ---

    @Test
    void inrTenFiftyStoresAsMinorUnits1050() {
        Money money = Money.of("10.50", Currency.INR);
        assertThat(money.toMinorUnits()).isEqualTo(1050);
        Money restored = Money.fromMinorUnits(1050L, Currency.INR);
        assertThat(restored).isEqualTo(money);
        assertThat(restored.getAmount().toPlainString()).isEqualTo("10.50");
    }

    @Test
    void jpyZeroDecimalPlacesRoundtrip() {
        Money money = Money.of("1500", Currency.JPY);
        assertThat(money.toMinorUnits()).isEqualTo(1500);
        Money restored = Money.fromMinorUnits(1500L, Currency.JPY);
        assertThat(restored).isEqualTo(money);
        assertThat(restored.getAmount().toPlainString()).isEqualTo("1500");
    }

    @Test
    void bhdThreeDecimalPlacesRoundtrip() {
        Money money = Money.of("1.234", Currency.BHD);
        assertThat(money.toMinorUnits()).isEqualTo(1234);
        Money restored = Money.fromMinorUnits(1234L, Currency.BHD);
        assertThat(restored).isEqualTo(money);
        assertThat(restored.getAmount().toPlainString()).isEqualTo("1.234");
    }

    @Test
    void kwdThreeDecimalPlacesRoundtrip() {
        Money money = Money.of("10.500", Currency.KWD);
        assertThat(money.toMinorUnits()).isEqualTo(10500);
        Money restored = Money.fromMinorUnits(10500L, Currency.KWD);
        assertThat(restored).isEqualTo(money);
    }

    @Test
    void jodThreeDecimalPlacesRoundtrip() {
        Money money = Money.of("5.100", Currency.JOD);
        assertThat(money.toMinorUnits()).isEqualTo(5100);
        Money restored = Money.fromMinorUnits(5100L, Currency.JOD);
        assertThat(restored).isEqualTo(money);
    }

    @Test
    void omrThreeDecimalPlacesRoundtrip() {
        Money money = Money.of("2.500", Currency.OMR);
        assertThat(money.toMinorUnits()).isEqualTo(2500);
        Money restored = Money.fromMinorUnits(2500L, Currency.OMR);
        assertThat(restored).isEqualTo(money);
    }

    @Test
    void invalidScaleForInrIsRejected() {
        // 10.505 has 3 decimal places; INR requires exactly 2.
        assertThatThrownBy(() -> Money.of("10.505", Currency.INR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Scale mismatch");
    }

    @Test
    void invalidScaleForBhdIsRejected() {
        // 1.23 has 2 decimal places; BHD requires exactly 3.
        assertThatThrownBy(() -> Money.of("1.23", Currency.BHD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Scale mismatch");
    }

    @Test
    void toMinorUnitsRejectsExcessPrecision() {
        // BigDecimal with scale 3 for INR (scale 2) must be caught at construction,
        // so toMinorUnits never sees a value that would truncate.
        assertThatThrownBy(() -> Money.of(new BigDecimal("10.505"), Currency.INR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Scale mismatch");
    }

    @Test
    void zeroAmountIsSupported() {
        Money zero = Money.zero(Currency.INR);
        assertThat(zero.toMinorUnits()).isZero();
        assertThat(zero.getAmount()).isEqualByComparingTo("0.00");
    }
}
