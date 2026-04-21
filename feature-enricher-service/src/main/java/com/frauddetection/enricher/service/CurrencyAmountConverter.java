package com.frauddetection.enricher.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

@Component
public class CurrencyAmountConverter {

    private static final Map<String, BigDecimal> PLN_RATES = Map.of(
            "PLN", BigDecimal.ONE,
            "EUR", BigDecimal.valueOf(4.30d),
            "USD", BigDecimal.valueOf(4.00d),
            "GBP", BigDecimal.valueOf(5.00d)
    );

    public BigDecimal toPln(BigDecimal amount, String currency) {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        String normalizedCurrency = currency == null ? "PLN" : currency.toUpperCase(Locale.ROOT);
        BigDecimal rate = PLN_RATES.getOrDefault(normalizedCurrency, BigDecimal.ONE);
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }
}
