package com.frauddetection.enricher.service;

import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.features.FraudFeatureThresholdContract;
import com.frauddetection.common.events.features.VelocityFeatureContract;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.enricher.config.FeatureStoreProperties;
import com.frauddetection.enricher.domain.FeatureStoreSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionFeatureCalculatorTest {

    private final TransactionFeatureCalculator calculator = new TransactionFeatureCalculator(
            new FeatureStoreProperties(
                    Duration.ofMinutes(1),
                    Duration.ofDays(7),
                    Duration.ofDays(8),
                    Duration.ofDays(180),
                    Duration.ofDays(30)
            ),
            new CurrencyAmountConverter()
    );

    @Test
    void shouldCalculateFraudRelevantFeatureFlags() {
        var event = TransactionFixtures.rawTransaction().build();
        var snapshot = new FeatureStoreSnapshot(
                4,
                new BigDecimal("4900.00"),
                new BigDecimal("4900.00"),
                List.of(),
                4,
                Instant.parse("2026-04-20T10:12:00Z"),
                false
        );

        var features = calculator.calculate(event, snapshot);

        assertThat(features.recentTransactionCount()).isEqualTo(5);
        assertThat(features.recentAmountSum().amount()).isEqualByComparingTo("6149.99");
        assertThat(features.deviceNovelty()).isTrue();
        assertThat(features.countryMismatch()).isFalse();
        assertThat(features.featureFlags()).contains(
                FraudFeatureContract.FLAG_DEVICE_NOVELTY,
                FraudFeatureContract.FLAG_MERCHANT_CONCENTRATION,
                FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY
        );
        assertThat(features.featureFlags()).doesNotContain(FraudFeatureContract.FLAG_HIGH_VELOCITY);
        assertThat(features.featureSnapshot()).containsEntry(FraudFeatureContract.MERCHANT_FREQUENCY_7D, 5);
    }

    @Test
    void shouldFlagRapidTransferBurstWhenShortWindowExceedsTwentyThousandPln() {
        var event = TransactionFixtures.rawTransaction()
                .withAmount(new BigDecimal("10000.00"), "PLN")
                .build();
        var snapshot = new FeatureStoreSnapshot(
                1,
                new BigDecimal("10000.00"),
                new BigDecimal("10000.00"),
                List.of(),
                1,
                Instant.parse("2026-04-20T10:12:00Z"),
                true
        );

        var features = calculator.calculate(event, snapshot);

        assertThat(features.featureFlags()).contains(FraudFeatureContract.FLAG_RAPID_PLN_20K_BURST);
        assertThat(features.featureSnapshot())
                .containsEntry(FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE, true)
                .containsEntry(FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN, new BigDecimal("20000.00"));
        assertThat(features.featureSnapshot().get(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW))
                .isEqualTo("PT1M")
                .isInstanceOf(String.class);
        assertThat(features.featureSnapshot().get(FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW))
                .isEqualTo("PT1M")
                .isInstanceOf(String.class);
        assertThat(features.featureSnapshot().get(FraudFeatureContract.RAPID_TRANSFER_WINDOW))
                .isEqualTo("PT1M")
                .isInstanceOf(String.class);
        assertThat(features.featureSnapshot().get(FraudFeatureContract.RECENT_AMOUNT_SUM))
                .isEqualTo(new BigDecimal("20000.00"))
                .isInstanceOf(BigDecimal.class);
        assertThat(features.featureSnapshot().get(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN))
                .isEqualTo(new BigDecimal("20000.00"))
                .isInstanceOf(BigDecimal.class);
        assertThat(features.featureSnapshot().get(FraudFeatureContract.RAPID_TRANSFER_THRESHOLD_PLN))
                .isEqualTo(new BigDecimal("20000"))
                .isInstanceOf(BigDecimal.class);
    }

    @Test
    void shouldKeepEarlyRapidTransfersLowVelocityUntilSeveralTransactionsAccumulate() {
        var event = TransactionFixtures.rawTransaction()
                .withAmount(new BigDecimal("10000.00"), "PLN")
                .build();
        var snapshot = new FeatureStoreSnapshot(
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(),
                1,
                Instant.parse("2026-04-20T10:12:00Z"),
                true
        );

        var features = calculator.calculate(event, snapshot);

        assertThat(features.featureFlags()).doesNotContain(
                FraudFeatureContract.FLAG_HIGH_VELOCITY,
                FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY,
                FraudFeatureContract.FLAG_RAPID_PLN_20K_BURST
        );
        assertThat(features.featureSnapshot())
                .containsEntry(FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE, false)
                .containsEntry(FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN, new BigDecimal("10000.00"));
    }

    @Test
    void shouldEmitOnlySharedContractFeatureKeysAndFlags() {
        var event = TransactionFixtures.rawTransaction().build();
        var snapshot = new FeatureStoreSnapshot(
                4,
                new BigDecimal("4900.00"),
                new BigDecimal("4900.00"),
                List.of(),
                4,
                Instant.parse("2026-04-20T10:12:00Z"),
                false
        );

        var features = calculator.calculate(event, snapshot);

        assertThat(features.featureSnapshot().keySet())
                .containsExactlyElementsOf(FraudFeatureContract.JAVA_ENRICHED_FEATURE_NAMES);
        assertThat(FraudFeatureContract.FEATURE_FLAGS_VALUES).containsAll(features.featureFlags());
    }

    @Test
    void featureStorePropertiesAcceptVelocityV1CanonicalObservationWindow() {
        var properties = properties(VelocityFeatureContract.CANONICAL_RECENT_TRANSACTION_COUNT_WINDOW);

        assertThat(properties.recentTransactionWindow())
                .isEqualTo(VelocityFeatureContract.CANONICAL_RECENT_TRANSACTION_COUNT_WINDOW);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT59S", "PT2M", "PT1H", "PT24H", "PT0S", "PT-1S"})
    void featureStorePropertiesRejectNonCanonicalVelocityV1ObservationWindow(String window) {
        assertThatThrownBy(() -> properties(Duration.parse(window)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.feature-store.recent-transaction-window")
                .hasMessageContaining("PT1M");
    }

    @ParameterizedTest
    @NullSource
    void featureStorePropertiesRejectMissingVelocityV1ObservationWindow(Duration window) {
        assertThatThrownBy(() -> properties(window))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.feature-store.recent-transaction-window")
                .hasMessageContaining("PT1M");
    }

    @Test
    void featureStorePropertiesRejectMalformedVelocityV1ObservationWindow() {
        assertThatThrownBy(() -> Duration.parse("not-a-duration"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void producedVelocityFeaturesUseCanonicalOneMinuteMeaning() {
        var event = TransactionFixtures.rawTransaction()
                .withAmount(new BigDecimal("100.00"), "PLN")
                .build();
        var snapshot = new FeatureStoreSnapshot(
                4,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(),
                0,
                Instant.parse("2026-04-20T10:12:00Z"),
                true
        );

        var features = calculator.calculate(event, snapshot);

        assertThat(features.recentTransactionCount()).isEqualTo(5);
        assertThat(features.recentTransactionCountWindow()).isEqualTo("PT1M");
        assertThat(features.transactionVelocityPerMinute()).isEqualTo(5.0d);
        assertThat(features.featureFlags()).doesNotContain(FraudFeatureContract.FLAG_HIGH_VELOCITY);
        assertThat(features.featureSnapshot())
                .containsEntry(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5)
                .containsEntry(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M")
                .containsEntry(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 5.0d);
    }

    private FeatureStoreProperties properties(Duration recentTransactionWindow) {
        return new FeatureStoreProperties(
                recentTransactionWindow,
                Duration.ofDays(7),
                Duration.ofDays(8),
                Duration.ofDays(180),
                Duration.ofDays(30)
        );
    }
}
