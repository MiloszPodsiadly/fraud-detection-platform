package com.frauddetection.alert.engineintelligence;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.engine-intelligence.pending-projection")
public record EngineIntelligencePendingProjectionProperties(
        boolean enabled,
        @Positive int batchSize,
        @Positive int maxAttempts,
        @NotNull Duration retryDelay,
        @NotNull Duration leaseDuration,
        @NotNull Duration maxAge
) {
    public EngineIntelligencePendingProjectionProperties {
        requirePositive(retryDelay, "retry-delay");
        requirePositive(leaseDuration, "lease-duration");
        requirePositive(maxAge, "max-age");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "app.engine-intelligence.pending-projection." + name + " must be positive"
            );
        }
    }
}
