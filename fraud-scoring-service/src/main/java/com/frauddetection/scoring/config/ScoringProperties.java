package com.frauddetection.scoring.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "app.scoring")
public record ScoringProperties(
        @DecimalMin("0.0") @DecimalMax("1.0") double highThreshold,
        @DecimalMin("0.0") @DecimalMax("1.0") double criticalThreshold,
        @NotNull ScoringMode mode
) {
}
