package com.frauddetection.alert.testfixtures;

import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionMapper;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionPolicy;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceComparisonReadModel;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceEngineReadModel;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadModel;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadModelMapper;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.MlModelIdentity;

import java.time.Instant;
import java.util.List;

/** Canonical test-only Engine Intelligence v2 scenarios shared by feedback and projection tests. */
public final class EngineIntelligenceV2TestFixtures {
    private static final Instant PROJECTION_GENERATED_AT = Instant.parse("2026-06-01T06:00:01Z");
    private static final Instant FEEDBACK_GENERATED_AT = Instant.parse("2026-06-25T09:00:03Z");
    private static final String RULES_ENGINE = "rules.primary";
    private static final String ML_ENGINE = "ml.python.primary";

    private EngineIntelligenceV2TestFixtures() {
    }

    public static EngineIntelligenceSummary rulesHighMlTimeoutSummary() {
        return rulesAvailableMlTimeoutSummary(
                RiskLevel.HIGH,
                EngineIntelligenceScoreBucket.HIGH,
                List.of("HIGH_VELOCITY", "DEVICE_NOVELTY"),
                PROJECTION_GENERATED_AT
        );
    }

    public static EngineIntelligenceSummary rulesCriticalMlTimeoutSummary() {
        return rulesAvailableMlTimeoutSummary(
                RiskLevel.CRITICAL,
                EngineIntelligenceScoreBucket.HIGH,
                List.of("HIGH_TRANSACTION_AMOUNT"),
                FEEDBACK_GENERATED_AT
        );
    }

    public static EngineIntelligenceSummary rulesCriticalMlAvailableSummary(MlModelIdentity modelIdentity) {
        EngineIntelligenceEngineResult rules = rules(
                RiskLevel.CRITICAL,
                EngineIntelligenceScoreBucket.HIGH,
                List.of("HIGH_TRANSACTION_AMOUNT")
        );
        EngineIntelligenceEngineResult ml = new EngineIntelligenceEngineResult(
                ML_ENGINE,
                FraudEngineType.ML_MODEL,
                FraudEngineStatus.AVAILABLE,
                RiskLevel.CRITICAL,
                EngineIntelligenceScoreBucket.HIGH,
                List.of("MODEL_HIGH_RISK"),
                modelIdentity
        );
        return new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                FEEDBACK_GENERATED_AT,
                List.of(rules, ml),
                comparison(EngineIntelligenceAgreementStatus.AGREEMENT,
                        EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL,
                        EngineIntelligenceScoreDeltaBucket.SMALL),
                List.of(),
                List.of()
        );
    }

    public static EngineIntelligenceSummary rulesLowMlHighAvailableSummary() {
        EngineIntelligenceEngineResult rules = rules(
                RiskLevel.LOW,
                EngineIntelligenceScoreBucket.LOW,
                List.of("HIGH_VELOCITY", "DEVICE_NOVELTY")
        );
        EngineIntelligenceEngineResult ml = new EngineIntelligenceEngineResult(
                ML_ENGINE,
                FraudEngineType.ML_MODEL,
                FraudEngineStatus.AVAILABLE,
                RiskLevel.HIGH,
                EngineIntelligenceScoreBucket.VERY_HIGH,
                List.of("ML_MODEL_SIGNAL"),
                new MlModelIdentity(
                        "python-logistic-fraud-model",
                        "2026-05-30.v1",
                        "2026-05-30.feature-contract.v1"
                )
        );
        return new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                PROJECTION_GENERATED_AT,
                List.of(rules, ml),
                comparison(EngineIntelligenceAgreementStatus.DISAGREEMENT,
                        EngineIntelligenceRiskMismatchStatus.MATERIAL_RISK_MISMATCH,
                        EngineIntelligenceScoreDeltaBucket.LARGE),
                List.of(),
                List.of()
        );
    }

    public static EngineIntelligenceReadModel rulesCriticalMlTimeoutReadModel(String transactionId) {
        return readModel(transactionId, rulesCriticalMlTimeoutSummary());
    }

    public static EngineIntelligenceReadModel rulesCriticalMlAvailableReadModel(
            String transactionId,
            MlModelIdentity modelIdentity
    ) {
        return readModel(transactionId, rulesCriticalMlAvailableSummary(modelIdentity));
    }

    /** Deliberately malformed v2 read model: required ML is absent, so response mapping must fail closed. */
    public static EngineIntelligenceReadModel negativeRulesOnlyV2ReadModel(String transactionId) {
        return EngineIntelligenceReadModel.projected(
                transactionId,
                EngineIntelligenceSummary.CONTRACT_VERSION,
                FEEDBACK_GENERATED_AT,
                comparisonReadModel(EngineIntelligenceAgreementStatus.PARTIAL,
                        EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                        EngineIntelligenceScoreDeltaBucket.UNAVAILABLE),
                List.of(new EngineIntelligenceEngineReadModel(
                        RULES_ENGINE,
                        FraudEngineType.RULES,
                        FraudEngineStatus.AVAILABLE,
                        RiskLevel.CRITICAL,
                        EngineIntelligenceScoreBucket.HIGH,
                        List.of("HIGH_TRANSACTION_AMOUNT")
                )),
                List.of(),
                List.of()
        );
    }

    private static EngineIntelligenceSummary rulesAvailableMlTimeoutSummary(
            RiskLevel rulesRisk,
            EngineIntelligenceScoreBucket rulesScoreBucket,
            List<String> rulesReasonCodes,
            Instant generatedAt
    ) {
        EngineIntelligenceEngineResult ml = new EngineIntelligenceEngineResult(
                ML_ENGINE,
                FraudEngineType.ML_MODEL,
                FraudEngineStatus.TIMEOUT,
                null,
                EngineIntelligenceScoreBucket.UNAVAILABLE,
                List.of("ML_MODEL_TIMEOUT")
        );
        return new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                generatedAt,
                List.of(rules(rulesRisk, rulesScoreBucket, rulesReasonCodes), ml),
                comparison(EngineIntelligenceAgreementStatus.PARTIAL,
                        EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                        EngineIntelligenceScoreDeltaBucket.UNAVAILABLE),
                List.of(),
                List.of()
        );
    }

    private static EngineIntelligenceEngineResult rules(
            RiskLevel risk,
            EngineIntelligenceScoreBucket scoreBucket,
            List<String> reasonCodes
    ) {
        return new EngineIntelligenceEngineResult(
                RULES_ENGINE,
                FraudEngineType.RULES,
                FraudEngineStatus.AVAILABLE,
                risk,
                scoreBucket,
                reasonCodes
        );
    }

    private static EngineIntelligenceComparison comparison(
            EngineIntelligenceAgreementStatus agreement,
            EngineIntelligenceRiskMismatchStatus mismatch,
            EngineIntelligenceScoreDeltaBucket delta
    ) {
        return new EngineIntelligenceComparison(
                EngineIntelligenceComparisonType.RULES_VS_ML,
                List.of(RULES_ENGINE, ML_ENGINE),
                agreement,
                mismatch,
                delta
        );
    }

    private static EngineIntelligenceComparisonReadModel comparisonReadModel(
            EngineIntelligenceAgreementStatus agreement,
            EngineIntelligenceRiskMismatchStatus mismatch,
            EngineIntelligenceScoreDeltaBucket delta
    ) {
        return new EngineIntelligenceComparisonReadModel(
                EngineIntelligenceComparisonType.RULES_VS_ML,
                List.of(RULES_ENGINE, ML_ENGINE),
                agreement,
                mismatch,
                delta
        );
    }

    private static EngineIntelligenceReadModel readModel(String transactionId, EngineIntelligenceSummary summary) {
        var result = new EngineIntelligenceProjectionMapper(new EngineIntelligenceProjectionPolicy())
                .map(transactionId, new ScoringOccurrenceOwnership(
                        "ei-v2-test-event", summary.generatedAt().minusSeconds(1), "a".repeat(64)), summary, null);
        var projection = result.projection().orElseThrow();
        return new EngineIntelligenceReadModelMapper().map(projection);
    }
}
