package com.frauddetection.scoring.engine.rules;

import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.features.FeatureSnapshotReaderFactory;
import com.frauddetection.scoring.features.FeatureSnapshotValueStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedSignalEngineFeatureStatusTest {

    private final RuleBasedSignalEngine engine = new RuleBasedSignalEngine(new FeatureSnapshotReaderFactory());

    @Test
    void presentTypedFeatureProducesBoundedSignal() {
        FraudEngineResult result = engine.evaluate(context(Map.of(FraudFeatureContract.DEVICE_NOVELTY, true)));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.reasonCodes()).containsExactly(RuleBasedSignalReasonCode.DEVICE_NOVELTY_SIGNAL.wireValue());
        assertThat(result.evidence()).extracting(evidence -> evidence.reasonCode())
                .containsExactly(RuleBasedSignalReasonCode.DEVICE_NOVELTY_SIGNAL.wireValue());
    }

    @Test
    void missingFeaturesSkipRulesWithoutInventingSafeEvidence() {
        FraudEngineResult result = engine.evaluate(context(Map.of()));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.reasonCodes()).isEmpty();
        assertThat(result.evidence()).isEmpty();
        assertThat(result.contributions()).isEmpty();
    }

    @Test
    void invalidTypesAreNotCoercedAndDoNotProduceValidSignals() {
        FraudEngineResult result = engine.evaluate(context(Map.of(
                FraudFeatureContract.DEVICE_NOVELTY, "true",
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, "3"
        )));

        assertThat(result.status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.score()).isNull();
        assertThat(result.riskLevel()).isNull();
        assertThat(result.statusReason()).isEqualTo(RuleBasedSignalReasonCode.FEATURE_STATUS_INVALID.wireValue());
        assertThat(flatten(result)).doesNotContain("true", "\"3\"");
        assertThat(result.reasonCodes()).containsExactly(RuleBasedSignalReasonCode.FEATURE_STATUS_INVALID.wireValue());
    }

    @Test
    void wrongAccessorDiagnosticDoesNotPretendSafeNoSignal() {
        FraudEngineResult result = RuleBasedSignalEngine.degradedResultFor(
                FeatureSnapshotValueStatus.WRONG_ACCESSOR,
                1L,
                Instant.parse("2026-05-30T10:00:00Z")
        );

        assertThat(result.status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.riskLevel()).isNull();
        assertThat(result.reasonCodes()).containsExactly(RuleBasedSignalReasonCode.FEATURE_STATUS_WRONG_ACCESSOR.wireValue());
    }

    @Test
    void notAllowedDiagnosticDoesNotExposeRawRejectedKeyOrPretendSafeNoSignal() {
        FraudEngineResult result = RuleBasedSignalEngine.degradedResultFor(
                FeatureSnapshotValueStatus.NOT_ALLOWED,
                1L,
                Instant.parse("2026-05-30T10:00:00Z")
        );

        assertThat(result.status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.score()).isNull();
        assertThat(result.reasonCodes()).containsExactly(RuleBasedSignalReasonCode.FEATURE_STATUS_NOT_ALLOWED.wireValue());
        assertThat(flatten(result)).doesNotContain("rawPayload", "token", "secret");
    }

    private ScoringContext context(Map<String, Object> featureSnapshot) {
        return new ScoringContext(
                TransactionFixtures.enrichedTransaction().build(),
                featureSnapshot,
                ScoringMode.RULE_BASED,
                "corr-rule-adapter-test",
                Instant.parse("2026-05-30T10:00:00Z")
        );
    }

    private String flatten(FraudEngineResult result) {
        return result.reasonCodes() + " " + result.contributions() + " " + result.evidence() + " " + result.statusReason();
    }
}
