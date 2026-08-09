package com.frauddetection.common.events.model;

import java.util.Locale;
import java.util.Set;

public final class SupportedCurrencyContract {
    public static final String SUPPORTED_CURRENCY_PATTERN = "^(PLN|EUR|USD|GBP)$";
    public static final Set<String> SUPPORTED_CURRENCIES = Set.of("PLN", "EUR", "USD", "GBP");

    private SupportedCurrencyContract() {
    }

    public static boolean isSupported(String currency) {
        return currency != null && SUPPORTED_CURRENCIES.contains(normalize(currency));
    }

    public static String normalize(String currency) {
        if (currency == null) {
            throw new IllegalArgumentException("SUPPORTED_CURRENCY_INVALID");
        }
        return currency.toUpperCase(Locale.ROOT);
    }
}
