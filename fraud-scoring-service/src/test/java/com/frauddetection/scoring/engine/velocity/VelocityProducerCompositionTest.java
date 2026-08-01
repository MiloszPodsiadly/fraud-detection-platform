package com.frauddetection.scoring.engine.velocity;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.features.VelocityFeatureContract;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.enricher.domain.EnrichedTransactionFeatures;
import com.frauddetection.enricher.domain.FeatureStoreSnapshot;
import com.frauddetection.enricher.domain.RecentTransaction;
import com.frauddetection.enricher.service.CurrencyAmountConverter;
import com.frauddetection.enricher.service.TransactionFeatureCalculator;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.features.FeatureSnapshotReaderFactory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VelocityProducerCompositionTest {
    private static final Instant RECEIVED_AT = Instant.parse("2026-06-18T10:00:00Z");

    @Test
    void realFeatureEnricherOutputIsAcceptedByVelocityReaderValidatorAndEngine() {
        ScoringContext context = contextFromRealFeatureEnricher();
        VelocityFeatureReader reader = new VelocityFeatureReader(new FeatureSnapshotReaderFactory());
        VelocityInputValidator validator = new VelocityInputValidator();
        VelocitySignalEngine engine = new VelocitySignalEngine(new FeatureSnapshotReaderFactory());

        VelocityInputs inputs = reader.read(context);
        VelocityInputValidation validation = validator.validate(inputs);
        var result = engine.evaluate(context);

        assertThat(inputs.recentTransactionCount().value()).isEqualTo(5);
        assertThat(inputs.recentTransactionCountWindow().value())
                .isEqualTo(VelocityFeatureContract.CANONICAL_RECENT_TRANSACTION_COUNT_WINDOW_TEXT);
        assertThat(inputs.transactionVelocityPerMinute().value()).isEqualTo(5.0d);
        assertThat(validation.readiness()).isEqualTo(VelocityInputReadiness.READY);
        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.score()).isEqualTo(0.75d);
        assertThat(result.reasonCodes()).containsExactly("TRANSACTION_VELOCITY");
    }

    private ScoringContext contextFromRealFeatureEnricher() {
        TransactionRawEvent raw = TransactionFixtures.rawTransaction()
                .withTransactionId("txn-velocity-producer-composition")
                .withAmount(new BigDecimal("100.00"), "PLN")
                .build();
        EnrichedTransactionFeatures features = new TransactionFeatureCalculator(new CurrencyAmountConverter())
                .calculate(raw, velocitySnapshot());
        TransactionEnrichedEvent enriched = new TransactionEnrichedEvent(
                raw.eventId(),
                raw.transactionId(),
                raw.correlationId(),
                raw.customerId(),
                raw.accountId(),
                raw.createdAt(),
                raw.transactionTimestamp(),
                raw.transactionAmount(),
                raw.merchantInfo(),
                raw.deviceInfo(),
                raw.locationInfo(),
                raw.customerContext(),
                features.recentTransactionCount(),
                features.recentTransactionCountWindow(),
                features.recentAmountSum(),
                features.recentAmountSumWindow(),
                features.transactionVelocityPerMinute(),
                features.merchantFrequency7d(),
                features.deviceNovelty(),
                features.countryMismatch(),
                features.proxyOrVpnDetected(),
                features.featureFlags(),
                features.featureSnapshot()
        );
        assertThat(enriched.featureFlags()).doesNotContain(FraudFeatureContract.FLAG_HIGH_VELOCITY);
        return new ScoringContext(enriched, enriched.featureSnapshot(), ScoringMode.ML, enriched.correlationId(), RECEIVED_AT);
    }

    private FeatureStoreSnapshot velocitySnapshot() {
        return new FeatureStoreSnapshot(
                4,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(
                        recentTransaction("hist-1"),
                        recentTransaction("hist-2"),
                        recentTransaction("hist-3"),
                        recentTransaction("hist-4")
                ),
                0,
                Instant.parse("2026-06-18T09:59:30Z"),
                true
        );
    }

    private RecentTransaction recentTransaction(String transactionId) {
        return new RecentTransaction(
                transactionId,
                Instant.parse("2026-06-18T09:59:30Z"),
                BigDecimal.ZERO,
                "PLN",
                BigDecimal.ZERO
        );
    }

}
