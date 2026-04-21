package com.frauddetection.simulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

@Validated
@ConfigurationProperties(prefix = "app.replay.synthetic")
public record SyntheticReplayProperties(
        @NotNull Instant referenceInstant,
        @Positive int customerCount,
        @Positive int merchantCount,
        @Positive int suspiciousEvery,
        @Positive long secondsBetweenEvents
) {
}
