package com.frauddetection.enricher.service;

import com.frauddetection.common.events.model.SupportedCurrencyContract;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
        if (!SupportedCurrencyContract.isSupported(currency)) {
            throw new IllegalArgumentException("SUPPORTED_CURRENCY_INVALID");
        }
        BigDecimal rate = PLN_RATES.get(SupportedCurrencyContract.normalize(currency));
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }
}
