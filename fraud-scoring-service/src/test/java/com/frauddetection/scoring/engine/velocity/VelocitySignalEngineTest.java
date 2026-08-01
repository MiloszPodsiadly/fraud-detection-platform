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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    void rapidBurstWithHighRateProducesCriticalDiagnosticSignal() {
        var result = engine.evaluate(context(producerSnapshot(5, "25000.00")));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.score()).isEqualTo(0.95d);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(result.confidence()).isEqualTo(FraudEngineConfidence.UNKNOWN);
        assertThat(result.reasonCodes()).containsExactly(
                "RAPID_PLN_20K_BURST",
                "TRANSACTION_VELOCITY"
        );
        assertThat(result.reasonCodes()).doesNotContain("RECENT_TRANSACTION_SPIKE");
        assertThat(result.contributions()).hasSize(2);
        assertThat(result.contributions()).extracting("value").containsOnlyNulls();
        assertThat(result.evidence()).hasSize(2);
        assertThat(result.evidence()).extracting("evidenceType").containsOnly(FraudEngineEvidenceType.VELOCITY_SIGNAL);
        assertThat(flatten(result)).doesNotContain("25000", "txn-", "cust-", "acct-", "card-");
    }

    @Test
    void retainedScoreTableBranchesAreReachableThroughProducerShapedFacts() {
        assertAvailable(producerSnapshot(5, "20000.00"), 0.95d, RiskLevel.CRITICAL);
        assertAvailable(producerSnapshot(2, "20000.00"), 0.80d, RiskLevel.HIGH);
        assertAvailable(producerSnapshot(5, "100.00"), 0.75d, RiskLevel.HIGH);
        assertAvailable(producerSnapshot(1, "20000.00"), 0.50d, RiskLevel.MEDIUM);
        assertAvailable(producerSnapshot(1, "100.00"), 0.10d, RiskLevel.LOW);
    }

    @Test
    void removedDeadBranchHasNoRemainingVelocityReasonOrScore() {
        var highAmountAndHighRate = engine.evaluate(context(producerSnapshot(5, "20000.00")));

        assertThat(highAmountAndHighRate.score()).isNotEqualTo(0.70d);
        assertThat(highAmountAndHighRate.reasonCodes()).doesNotContain("RECENT_TRANSACTION_SPIKE");
    }

    @Test
    void velocityScoreIsMonotonicAcrossCanonicalProducerSignals() {
        double baseline = score(producerSnapshot(1, "100.00"));
        double highAmount = score(producerSnapshot(1, "20000.00"));
        double highRate = score(producerSnapshot(5, "100.00"));
        double rapidBurst = score(producerSnapshot(2, "20000.00"));
        double rapidBurstAndHighRate = score(producerSnapshot(5, "20000.00"));

        assertThat(highAmount).isGreaterThanOrEqualTo(baseline);
        assertThat(highRate).isGreaterThanOrEqualTo(highAmount);
        assertThat(rapidBurst).isGreaterThanOrEqualTo(highRate);
        assertThat(rapidBurstAndHighRate).isGreaterThanOrEqualTo(rapidBurst);
    }

    @Test
    void upstreamRapidTransferCandidateIsIgnoredAsDecisionInput() {
        Map<String, Object> forgedFalse = producerSnapshot(2, "20000.00");
        forgedFalse.put(FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE, false);
        Map<String, Object> forgedTrue = producerSnapshot(1, "100.00");
        forgedTrue.put(FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE, true);

        assertThat(engine.evaluate(context(forgedFalse)).reasonCodes()).contains("RAPID_PLN_20K_BURST");
        assertThat(engine.evaluate(context(forgedTrue)).reasonCodes()).doesNotContain("RAPID_PLN_20K_BURST");
    }

    @Test
    void missingOnlyVelocityFeatureIsUnavailable() {
        Map<String, Object> featureSnapshot = producerSnapshot(5, "25000.00");
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
    void missingWindowIsUnavailable() {
        Map<String, Object> featureSnapshot = producerSnapshot(5, "25000.00");
        featureSnapshot.remove(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW);

        var result = engine.evaluate(context(featureSnapshot));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.UNAVAILABLE);
        assertThat(result.statusReason()).isEqualTo("VELOCITY_FEATURES_UNAVAILABLE");
    }

    @Test
    void invalidTypeTakesPrecedenceOverMissing() {
        Map<String, Object> featureSnapshot = producerSnapshot(5, "25000.00");
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
        Map<String, Object> featureSnapshot = producerSnapshot(5, "25000.00");
        featureSnapshot.put(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, Double.NaN);
        featureSnapshot.remove(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN);

        var result = engine.evaluate(context(featureSnapshot));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.statusReason()).isEqualTo("VELOCITY_FEATURE_VALUE_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT59S", "PT2M", "PT1H", "PT24H", "PT0S", "PT-1S", "not-a-duration"})
    void nonCanonicalWindowIsDegradedInvalidValue(String window) {
        Map<String, Object> featureSnapshot = producerSnapshot(5, "25000.00");
        featureSnapshot.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, window);

        var result = engine.evaluate(context(featureSnapshot));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.statusReason()).isEqualTo("VELOCITY_FEATURE_VALUE_INVALID");
        assertThat(result.score()).isNull();
        assertThat(result.riskLevel()).isNull();
    }

    @Test
    void oversizedWindowTextIsRejectedBeforeDurationParsing() {
        Map<String, Object> featureSnapshot = producerSnapshot(5, "25000.00");
        featureSnapshot.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "P" + "1".repeat(40) + "D");

        var result = engine.evaluate(context(featureSnapshot));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.statusReason()).isEqualTo("VELOCITY_FEATURE_VALUE_INVALID");
    }

    @Test
    void invalidDomainValuesAreDegraded() {
        assertThat(engine.evaluate(context(snapshot(-1, "PT1M", "100.00", 1.0d))).statusReason())
                .isEqualTo("VELOCITY_FEATURE_VALUE_INVALID");
        assertThat(engine.evaluate(context(snapshot(1, "PT1M", "-0.01", 1.0d))).statusReason())
                .isEqualTo("VELOCITY_FEATURE_VALUE_INVALID");
        assertThat(engine.evaluate(context(snapshot(1, "PT1M", "100.00", Double.POSITIVE_INFINITY))).statusReason())
                .isEqualTo("VELOCITY_FEATURE_VALUE_INVALID");
    }

    @Test
    void impossiblePresentFactualRelationshipIsDegraded() {
        var result = engine.evaluate(context(snapshot(0, "PT1M", "0.01", 0.0d)));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.statusReason()).isEqualTo("VELOCITY_FEATURES_INCONSISTENT");
    }

    @Test
    void zeroFactsAreValidZero() {
        var result = engine.evaluate(context(snapshot(0, "PT1M", "0.00", -0.0d)));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.score()).isEqualTo(0.10d);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void authoritativeThresholdsAndWindowComeFromSharedFeatureContract() {
        assertThat(FraudFeatureThresholdContract.VELOCITY_V1_OBSERVATION_WINDOW.toString()).isEqualTo("PT1M");
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

    private Map<String, Object> producerSnapshot(int count, String amountPln) {
        return snapshot(count, "PT1M", amountPln, (double) count);
    }

    private Map<String, Object> snapshot(int count, String window, String amountPln, double velocityPerMinute) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT, count);
        snapshot.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, window);
        snapshot.put(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal(amountPln));
        snapshot.put(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, velocityPerMinute);
        snapshot.put(FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, "txn-secret");
        return snapshot;
    }

    private String flatten(Object value) {
        return String.valueOf(value);
    }
}
