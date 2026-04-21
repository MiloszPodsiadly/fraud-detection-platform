package com.frauddetection.enricher.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.feature-store")
public record FeatureStoreProperties(
        @NotNull Duration recentTransactionWindow,
        @NotNull Duration merchantFrequencyWindow,
        @NotNull Duration transactionKeyTtl,
        @NotNull Duration knownDeviceTtl,
        @NotNull Duration lastTransactionTtl
) {
}
