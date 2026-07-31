package com.frauddetection.scoring.engine.velocity;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
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
        assertThat(engine.descriptor().version()).isEqualTo("velocity-v1");
        assertThat(engine.descriptor().required()).isFalse();
    }

    @Test
    void rapidBurstWithCountOrRateProducesCriticalDiagnosticSignal() {
        var result = engine.evaluate(context(snapshot(5, "25000.00", 5.0d)));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.score()).isEqualTo(0.95d);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(result.confidence()).isEqualTo(FraudEngineConfidence.UNKNOWN);
        assertThat(result.reasonCodes()).containsExactly(
                "RAPID_PLN_20K_BURST",
                "TRANSACTION_VELOCITY",
                "RECENT_TRANSACTION_SPIKE",
                "RECENT_AMOUNT_ACCUMULATION"
        );
        assertThat(result.contributions()).hasSize(4);
        assertThat(result.contributions()).extracting("value").containsOnlyNulls();
        assertThat(result.evidence()).hasSize(4);
        assertThat(result.evidence()).extracting("evidenceType").containsOnly(FraudEngineEvidenceType.VELOCITY_SIGNAL);
        assertThat(result.generatedAt()).isNotEqualTo(RECEIVED_AT);
        assertThat(flatten(result)).doesNotContain("25000", "txn-", "cust-", "acct-", "card-");
    }

    @Test
    void scoreTableBranchesAreReachableThroughRealEngineValidation() {
        assertAvailable(snapshot(5, "20000.00", 5.0d), 0.95d, RiskLevel.CRITICAL);
        assertAvailable(snapshot(2, "20000.00", 1.0d), 0.80d, RiskLevel.HIGH);
        assertAvailable(snapshot(5, "100.00", 5.0d), 0.75d, RiskLevel.HIGH);
        assertAvailable(snapshot(1, "20000.00", 5.0d), 0.70d, RiskLevel.MEDIUM);
        assertAvailable(snapshot(1, "20000.00", 1.0d), 0.50d, RiskLevel.MEDIUM);
        assertAvailable(snapshot(1, "100.00", 1.0d), 0.10d, RiskLevel.LOW);
    }

    @Test
    void velocityScoreIsMonotonicAcrossCanonicalSignals() {
        double baseline = score(snapshot(1, "100.00", 1.0d));
        double countSpike = score(snapshot(5, "100.00", 1.0d));
        double countAndRate = score(snapshot(5, "100.00", 5.0d));
        double highAmount = score(snapshot(1, "20000.00", 1.0d));
        double highAmountAndRate = score(snapshot(1, "20000.00", 5.0d));
        double rapidBurst = score(snapshot(2, "20000.00", 5.0d));

        assertThat(countSpike).isGreaterThanOrEqualTo(baseline);
        assertThat(countAndRate).isGreaterThanOrEqualTo(countSpike);
        assertThat(highAmount).isGreaterThanOrEqualTo(baseline);
        assertThat(highAmountAndRate).isGreaterThanOrEqualTo(highAmount);
        assertThat(rapidBurst).isGreaterThanOrEqualTo(highAmountAndRate);
    }

    @Test
    void upstreamRapidTransferCandidateIsIgnoredAsDecisionInput() {
        Map<String, Object> forgedFalse = snapshot(2, "20000.00", 1.0d);
        forgedFalse.put(FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE, false);
        Map<String, Object> forgedTrue = snapshot(1, "100.00", 1.0d);
        forgedTrue.put(FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE, true);

        assertThat(engine.evaluate(context(forgedFalse)).reasonCodes()).contains("RAPID_PLN_20K_BURST");
        assertThat(engine.evaluate(context(forgedTrue)).reasonCodes()).doesNotContain("RAPID_PLN_20K_BURST");
    }

    @Test
    void missingOnlyVelocityFeatureIsUnavailable() {
        Map<String, Object> featureSnapshot = snapshot(5, "25000.00", 5.0d);
        featureSnapshot.remove(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE);

        var result = engine.evaluate(context(featureSnapshot));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.UNAVAILABLE);
        assertThat(result.score()).isNull();
        assertThat(result.riskLevel()).isNull();
        assertThat(result.confidence()).isEqualTo(FraudEngineConfidence.UNKNOWN);
        assertThat(result.statusReason()).isEqualTo("VELOCITY_FEATURES_UNAVAILABLE");
        assertThat(result.reasonCodes()).containsExactly("VELOCITY_FEATURES_UNAVAILABLE");
    }

    @Test
    void invalidTypeTakesPrecedenceOverMissing() {
        Map<String, Object> featureSnapshot = snapshot(5, "25000.00", 5.0d);
        featureSnapshot.put(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, "25000.00");
        featureSnapshot.remove(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE);

        var result = engine.evaluate(context(featureSnapshot));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.score()).isNull();
        assertThat(result.riskLevel()).isNull();
        assertThat(result.statusReason()).isEqualTo("VELOCITY_FEATURE_TYPE_INVALID");
    }

    @Test
    void invalidDomainTakesPrecedenceOverMissing() {
        Map<String, Object> featureSnapshot = snapshot(5, "25000.00", Double.NaN);
        featureSnapshot.remove(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN);

        var result = engine.evaluate(context(featureSnapshot));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.statusReason()).isEqualTo("VELOCITY_FEATURE_VALUE_INVALID");
    }

    @Test
    void invalidDomainValuesAreDegraded() {
        assertThat(engine.evaluate(context(snapshot(-1, "100.00", 1.0d))).statusReason())
                .isEqualTo("VELOCITY_FEATURE_VALUE_INVALID");
        assertThat(engine.evaluate(context(snapshot(1, "-0.01", 1.0d))).statusReason())
                .isEqualTo("VELOCITY_FEATURE_VALUE_INVALID");
        assertThat(engine.evaluate(context(snapshot(1, "100.00", Double.POSITIVE_INFINITY))).statusReason())
                .isEqualTo("VELOCITY_FEATURE_VALUE_INVALID");

        Map<String, Object> malformedWindow = snapshot(1, "100.00", 1.0d);
        malformedWindow.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "not-a-duration");
        assertThat(engine.evaluate(context(malformedWindow)).statusReason()).isEqualTo("VELOCITY_FEATURE_VALUE_INVALID");

        Map<String, Object> zeroWindow = snapshot(1, "100.00", 1.0d);
        zeroWindow.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT0S");
        assertThat(engine.evaluate(context(zeroWindow)).statusReason()).isEqualTo("VELOCITY_FEATURE_VALUE_INVALID");

        Map<String, Object> oversizedWindow = snapshot(1, "100.00", 1.0d);
        oversizedWindow.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "P2D");
        assertThat(engine.evaluate(context(oversizedWindow)).statusReason()).isEqualTo("VELOCITY_FEATURE_VALUE_INVALID");
    }

    @Test
    void impossiblePresentFactualRelationshipIsDegraded() {
        var result = engine.evaluate(context(snapshot(0, "0.01", 0.0d)));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.statusReason()).isEqualTo("VELOCITY_FEATURES_INCONSISTENT");
    }

    @Test
    void zeroFactsAreValidZero() {
        var result = engine.evaluate(context(snapshot(0, "0.00", -0.0d)));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.score()).isEqualTo(0.10d);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void authoritativeThresholdsComeFromSharedFeatureContract() {
        assertThat(FraudFeatureThresholdContract.RAPID_TRANSFER_MIN_COUNT).isEqualTo(2);
        assertThat(FraudFeatureThresholdContract.RAPID_TRANSFER_PLN_THRESHOLD).isEqualByComparingTo("20000");
        assertThat(FraudFeatureThresholdContract.HIGH_VELOCITY_TRANSACTION_COUNT).isEqualTo(5);
    }

    private void assertAvailable(Map<String, Object> featureSnapshot, double score, RiskLevel riskLevel) {
        var result = engine.evaluate(context(featureSnapshot));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.score()).isEqualTo(score);
        assertThat(result.riskLevel()).isEqualTo(riskLevel);
        assertThat(result.score()).isBetween(0.0d, 1.0d);
        assertThat(result.confidence()).isEqualTo(FraudEngineConfidence.UNKNOWN);
    }

    private double score(Map<String, Object> featureSnapshot) {
        return engine.evaluate(context(featureSnapshot)).score();
    }

    private ScoringContext context(Map<String, Object> featureSnapshot) {
        var event = TransactionFixtures.enrichedTransaction().build();
        return new ScoringContext(event, featureSnapshot, ScoringMode.ML, event.correlationId(), RECEIVED_AT);
    }

    private Map<String, Object> snapshot(int count, String amountPln, double velocityPerMinute) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT, count);
        snapshot.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M");
        snapshot.put(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal(amountPln));
        snapshot.put(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, velocityPerMinute);
        return snapshot;
    }

    private String flatten(Object value) {
        return String.valueOf(value);
    }
}
