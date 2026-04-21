package com.frauddetection.alert.api;

import java.math.BigDecimal;

public record MoneyResponse(
        BigDecimal amount,
        String currency
) {
}
