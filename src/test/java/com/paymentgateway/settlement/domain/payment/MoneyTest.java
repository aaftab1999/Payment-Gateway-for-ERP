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
}
