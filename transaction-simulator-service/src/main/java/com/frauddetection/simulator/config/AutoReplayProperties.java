package com.frauddetection.simulator.config;

import com.frauddetection.simulator.api.ReplaySourceType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

@Validated
@ConfigurationProperties(prefix = "app.replay.auto")
public record AutoReplayProperties(
        boolean enabled,
        @NotNull ReplaySourceType sourceType,
        @Positive Integer maxEvents,
        @PositiveOrZero Long throttleMillis,
        @PositiveOrZero Long startDelayMillis
) {
}
