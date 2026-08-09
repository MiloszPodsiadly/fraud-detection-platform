package com.frauddetection.common.events.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportedCurrencyContractTest {

    @ParameterizedTest
    @ValueSource(strings = {"PLN", "EUR", "USD", "GBP", "pln"})
    void recognizesSupportedCurrencies(String currency) {
        assertThat(SupportedCurrencyContract.isSupported(currency)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"JPY", "XXX", ""})
    void rejectsUnsupportedCurrencies(String currency) {
        assertThat(SupportedCurrencyContract.isSupported(currency)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pln", "eur", "usd", "gbp"})
    void normalizesCurrency(String currency) {
        assertThat(SupportedCurrencyContract.normalize(currency)).isEqualTo(currency.toUpperCase());
    }

    @ParameterizedTest
    @NullSource
    void normalizeRejectsNullCurrency(String currency) {
        assertThatThrownBy(() -> SupportedCurrencyContract.normalize(currency))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SUPPORTED_CURRENCY_INVALID");
    }
}
