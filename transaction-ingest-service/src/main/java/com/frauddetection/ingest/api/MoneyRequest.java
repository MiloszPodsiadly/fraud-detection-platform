package com.frauddetection.ingest.api;

import com.frauddetection.common.events.model.SupportedCurrencyContract;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record MoneyRequest(
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
        @NotBlank
        @Size(min = 3, max = 3)
        @Pattern(regexp = SupportedCurrencyContract.SUPPORTED_CURRENCY_PATTERN)
        String currency
) {
}
