package com.frauddetection.common.events.model;

import java.math.BigDecimal;

public record Money(
        BigDecimal amount,
        String currency
) {
}
