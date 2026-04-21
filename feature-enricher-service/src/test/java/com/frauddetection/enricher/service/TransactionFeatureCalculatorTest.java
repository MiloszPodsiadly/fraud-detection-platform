package com.frauddetection.enricher.service;

import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.enricher.config.FeatureStoreProperties;
import com.frauddetection.enricher.domain.FeatureStoreSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionFeatureCalculatorTest {

    private final TransactionFeatureCalculator calculator = new TransactionFeatureCalculator(
            new FeatureStoreProperties(
                    Duration.ofMinutes(15),
                    Duration.ofDays(7),
                    Duration.ofDays(8),
                    Duration.ofDays(180),
                    Duration.ofDays(30)
            )
    );

    @Test
    void shouldCalculateFraudRelevantFeatureFlags() {
        var event = TransactionFixtures.rawTransaction().build();
        var snapshot = new FeatureStoreSnapshot(
                4,
                new BigDecimal("4900.00"),
                4,
                Instant.parse("2026-04-20T10:12:00Z"),
                false
        );

        var features = calculator.calculate(event, snapshot);

        assertThat(features.recentTransactionCount()).isEqualTo(5);
        assertThat(features.recentAmountSum().amount()).isEqualByComparingTo("6149.99");
        assertThat(features.deviceNovelty()).isTrue();
        assertThat(features.countryMismatch()).isFalse();
        assertThat(features.featureFlags()).contains("DEVICE_NOVELTY", "HIGH_VELOCITY", "MERCHANT_CONCENTRATION", "HIGH_AMOUNT_ACTIVITY");
        assertThat(features.featureSnapshot()).containsEntry("merchantFrequency7d", 5);
    }
}
