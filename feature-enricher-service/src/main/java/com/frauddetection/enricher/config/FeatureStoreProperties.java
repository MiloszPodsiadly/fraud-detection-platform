package com.frauddetection.enricher.config;

import com.frauddetection.common.events.features.VelocityFeatureContract;
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
    public FeatureStoreProperties {
        if (!VelocityFeatureContract.CANONICAL_RECENT_TRANSACTION_COUNT_WINDOW.equals(recentTransactionWindow)) {
            throw new IllegalArgumentException(
                    "app.feature-store.recent-transaction-window must be PT1M for Velocity v1 time basis"
            );
        }
    }
}
