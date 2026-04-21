package com.frauddetection.simulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

@Validated
@ConfigurationProperties(prefix = "app.replay")
public record ReplayProperties(
        @PositiveOrZero Long throttleMillis,
        @NotBlank String sourceType
) {
}
