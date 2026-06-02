package com.frauddetection.alert.engineintelligence;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.events.intelligence.EngineIntelligenceDiagnosticSignal;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSignalCategory;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningCode;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningSummary;

import java.time.Instant;
import java.util.List;
import java.util.Map;

final class EngineIntelligenceProjectionTestFixtures {

    static final Instant GENERATED_AT = Instant.parse("2026-06-01T06:00:01Z");

    private EngineIntelligenceProjectionTestFixtures() {
    }

    static EngineIntelligenceSummary minimalSummary() {
        return new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                GENERATED_AT,
                List.of(availableRules(RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH)),
                comparison(
                        EngineIntelligenceAgreementStatus.INSUFFICIENT_DATA,
                        EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                        EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
                ),
                List.of(),
                List.of()
        );
    }

    static EngineIntelligenceSummary fullSummary() {
        return new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                GENERATED_AT,
                List.of(
                        availableRules(RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH),
                        timeoutMl()
                ),
                comparison(
                        EngineIntelligenceAgreementStatus.PARTIAL,
                        EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                        EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
                ),
                List.of(
                        new EngineIntelligenceDiagnosticSignal(
                                "rules.primary",
                                FraudEngineType.RULES,
                                FraudEngineStatus.AVAILABLE,
                                EngineIntelligenceSignalCategory.FRAUD_SIGNAL,
                                RiskLevel.HIGH,
                                EngineIntelligenceScoreBucket.HIGH,
                                "HIGH_VELOCITY"
                        ),
                        operationalMlSignal()
                ),
                List.of(
                        new EngineIntelligenceWarningSummary(EngineIntelligenceWarningCode.EVIDENCE_UNSAFE_DROPPED, 1),
                        new EngineIntelligenceWarningSummary(EngineIntelligenceWarningCode.REASON_CODE_LIMIT_APPLIED, 1)
                )
        );
    }

    static EngineIntelligenceSummary disagreementSummary() {
        return new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                GENERATED_AT,
                List.of(
                        availableRules(RiskLevel.LOW, EngineIntelligenceScoreBucket.LOW),
                        new EngineIntelligenceEngineResult(
                                "ml.python.primary",
                                FraudEngineType.ML_MODEL,
                                FraudEngineStatus.AVAILABLE,
                                RiskLevel.HIGH,
                                EngineIntelligenceScoreBucket.VERY_HIGH,
                                List.of("ML_MODEL_SIGNAL")
                        )
                ),
                comparison(
                        EngineIntelligenceAgreementStatus.DISAGREEMENT,
                        EngineIntelligenceRiskMismatchStatus.MATERIAL_RISK_MISMATCH,
                        EngineIntelligenceScoreDeltaBucket.LARGE
                ),
                List.of(),
                List.of()
        );
    }

    static EngineIntelligenceEngineResult availableRules(RiskLevel riskLevel, EngineIntelligenceScoreBucket scoreBucket) {
        return new EngineIntelligenceEngineResult(
                "rules.primary",
                FraudEngineType.RULES,
                FraudEngineStatus.AVAILABLE,
                riskLevel,
                scoreBucket,
                List.of("HIGH_VELOCITY", "DEVICE_NOVELTY")
        );
    }

    static EngineIntelligenceEngineResult timeoutMl() {
        return new EngineIntelligenceEngineResult(
                "ml.python.primary",
                FraudEngineType.ML_MODEL,
                FraudEngineStatus.TIMEOUT,
                null,
                EngineIntelligenceScoreBucket.UNAVAILABLE,
                List.of("ML_MODEL_TIMEOUT")
        );
    }

    static EngineIntelligenceDiagnosticSignal operationalMlSignal() {
        return new EngineIntelligenceDiagnosticSignal(
                "ml.python.primary",
                FraudEngineType.ML_MODEL,
                FraudEngineStatus.TIMEOUT,
                EngineIntelligenceSignalCategory.OPERATIONAL_SIGNAL,
                null,
                EngineIntelligenceScoreBucket.UNAVAILABLE,
                "ML_MODEL_TIMEOUT"
        );
    }

    static EngineIntelligenceComparison comparison(
            EngineIntelligenceAgreementStatus agreementStatus,
            EngineIntelligenceRiskMismatchStatus riskMismatchStatus,
            EngineIntelligenceScoreDeltaBucket scoreDeltaBucket
    ) {
        return new EngineIntelligenceComparison(agreementStatus, riskMismatchStatus, scoreDeltaBucket);
    }

    static TransactionScoredEvent oldEvent() {
        return event(null);
    }

    static TransactionScoredEvent event(EngineIntelligenceSummary engineIntelligence) {
        return new TransactionScoredEvent(
                "evt-fdp95-001",
                "txn-fdp95-001",
                "corr-fdp95-001",
                "cust-fdp95-001",
                "acct-fdp95-001",
                GENERATED_AT.minusSeconds(1),
                GENERATED_AT.minusSeconds(2),
                null,
                null,
                null,
                null,
                null,
                0.82d,
                RiskLevel.HIGH,
                "RULE_BASED",
                "rule-engine",
                "v1",
                GENERATED_AT,
                List.of("HIGH_VELOCITY"),
                Map.of(),
                Map.of(),
                true,
                List.of(),
                engineIntelligence
        );
    }
}
