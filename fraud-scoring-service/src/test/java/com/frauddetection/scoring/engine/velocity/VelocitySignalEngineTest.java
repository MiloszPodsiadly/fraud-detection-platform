package com.frauddetection.scoring.engine.velocity;

import com.frauddetection.common.events.engine.FraudEngineEvidenceType;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.features.FraudFeatureThresholdContract;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.features.FeatureSnapshotReaderFactory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VelocitySignalEngineTest {
    private static final Instant RECEIVED_AT = Instant.parse("2026-05-30T10:00:00Z");

    private final VelocitySignalEngine engine = new VelocitySignalEngine(new FeatureSnapshotReaderFactory());

    @Test
    void descriptorDeclaresOptionalDiagnosticVelocityEngine() {
        assertThat(engine.descriptor().engineId()).isEqualTo(FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID);
        assertThat(engine.descriptor().engineType()).isEqualTo(FraudEngineType.VELOCITY);
        assertThat(engine.descriptor().engineLanguage()).isEqualTo("java");
        assertThat(engine.descriptor().version()).isEqualTo("1.0.0");
        assertThat(engine.descriptor().required()).isFalse();
    }

    @Test
    void rapidBurstWithCountOrRateProducesCriticalDiagnosticSignal() {
        var result = engine.evaluate(context(snapshot(5, "25000.00", 5.0d, true)));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.score()).isEqualTo(0.95d);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(result.reasonCodes()).containsExactly(
                "RAPID_PLN_20K_BURST",
                "TRANSACTION_VELOCITY",
                "RECENT_TRANSACTION_SPIKE",
                "RECENT_AMOUNT_ACCUMULATION"
        );
        assertThat(result.contributions()).hasSize(4);
        assertThat(result.evidence()).hasSize(4);
        assertThat(result.evidence()).extracting("evidenceType").containsOnly(FraudEngineEvidenceType.VELOCITY_SIGNAL);
        assertThat(flatten(result)).doesNotContain("25000", "txn-", "cust-", "acct-", "card-");
    }

    @Test
    void scoreTableUsesMostSpecificVelocitySignals() {
        assertThat(decision(5, "20000.00", 5.0d, true).score()).isEqualTo(0.95d);
        assertThat(decision(1, "20000.00", 1.0d, true).score()).isEqualTo(0.80d);
        assertThat(decision(5, "100.00", 5.0d, false).score()).isEqualTo(0.75d);
        assertThat(decision(5, "20000.00", 1.0d, false).score()).isEqualTo(0.70d);
        assertThat(decision(1, "20000.00", 1.0d, false).score()).isEqualTo(0.50d);
        assertThat(decision(1, "100.00", 1.0d, false).score()).isEqualTo(0.10d);
    }

    @Test
    void missingRequiredVelocityFeatureIsUnavailable() {
        Map<String, Object> featureSnapshot = snapshot(5, "25000.00", 5.0d, true);
        featureSnapshot.remove(FraudFeatureContract.RAPID_TRANSFER_THRESHOLD_PLN);

        var result = engine.evaluate(context(featureSnapshot));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.UNAVAILABLE);
        assertThat(result.score()).isNull();
        assertThat(result.riskLevel()).isNull();
        assertThat(result.confidence().name()).isEqualTo("UNKNOWN");
        assertThat(result.statusReason()).isEqualTo("VELOCITY_FEATURES_UNAVAILABLE");
        assertThat(result.reasonCodes()).containsExactly("VELOCITY_FEATURES_UNAVAILABLE");
    }

    @Test
    void invalidFeatureTypeIsDegraded() {
        Map<String, Object> featureSnapshot = snapshot(5, "25000.00", 5.0d, true);
        featureSnapshot.put(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, "25000.00");

        var result = engine.evaluate(context(featureSnapshot));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.score()).isNull();
        assertThat(result.riskLevel()).isNull();
        assertThat(result.statusReason()).isEqualTo("VELOCITY_FEATURE_TYPE_INVALID");
    }

    @Test
    void invalidDomainValueIsDegraded() {
        Map<String, Object> featureSnapshot = snapshot(5, "25000.00", 5.0d, true);
        featureSnapshot.put(FraudFeatureContract.RAPID_TRANSFER_WINDOW, "PT0S");

        var result = engine.evaluate(context(featureSnapshot));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.statusReason()).isEqualTo("VELOCITY_FEATURE_VALUE_INVALID");
    }

    @Test
    void inconsistentRedundantValuesAreDegraded() {
        Map<String, Object> featureSnapshot = snapshot(5, "25000.00", 5.0d, true);
        featureSnapshot.put(FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN, new BigDecimal("24999.99"));

        var result = engine.evaluate(context(featureSnapshot));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.statusReason()).isEqualTo("VELOCITY_FEATURES_INCONSISTENT");
    }

    @Test
    void authoritativeThresholdComesFromSharedFeatureContract() {
        assertThat(VelocitySignalPolicy.RAPID_TRANSFER_PLN_THRESHOLD)
                .isEqualByComparingTo(FraudFeatureThresholdContract.RAPID_TRANSFER_PLN_THRESHOLD);
        assertThat(VelocitySignalPolicy.HIGH_VELOCITY_TRANSACTION_COUNT)
                .isEqualTo(FraudFeatureThresholdContract.HIGH_VELOCITY_TRANSACTION_COUNT);
    }

    private ScoringContext context(Map<String, Object> featureSnapshot) {
        var event = TransactionFixtures.enrichedTransaction().build();
        return new ScoringContext(event, featureSnapshot, ScoringMode.ML, event.correlationId(), RECEIVED_AT);
    }

    private Map<String, Object> snapshot(int count, String amountPln, double velocityPerMinute, boolean candidate) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT, count);
        snapshot.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M");
        snapshot.put(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal(amountPln));
        snapshot.put(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, velocityPerMinute);
        snapshot.put(FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE, candidate);
        snapshot.put(FraudFeatureContract.RAPID_TRANSFER_COUNT, count);
        snapshot.put(FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN, new BigDecimal(amountPln));
        snapshot.put(FraudFeatureContract.RAPID_TRANSFER_WINDOW, "PT1M");
        snapshot.put(FraudFeatureContract.RAPID_TRANSFER_THRESHOLD_PLN, new BigDecimal("20000"));
        return snapshot;
    }

    private VelocitySignalPolicy.VelocityDecision decision(
            int count,
            String amountPln,
            double velocityPerMinute,
            boolean candidate
    ) {
        return VelocitySignalPolicy.decide(new VelocitySignalPolicy.VelocityFacts(
                count,
                new BigDecimal(amountPln),
                velocityPerMinute,
                candidate
        ));
    }

    private String flatten(Object value) {
        return String.valueOf(value);
    }
}
