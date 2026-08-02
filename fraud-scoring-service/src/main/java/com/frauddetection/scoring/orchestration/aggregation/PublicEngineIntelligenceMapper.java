package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceDiagnosticSignal;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSignalCategory;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningCode;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningSummary;

import java.util.List;
import java.util.Objects;

public final class PublicEngineIntelligenceMapper {

    public EngineIntelligenceSummary map(FraudEngineAggregationResult result) {
        Objects.requireNonNull(result, "result is required");
        List<EngineIntelligenceEngineResult> engines = result.normalizedEngineResults()
                .stream()
                .map(this::mapEngineResult)
                .toList();
        return new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                result.generatedAt(),
                engines,
                mapComparison(engines),
                result.strongestSignals().stream().map(this::mapDiagnosticSignal).toList(),
                FraudEngineAggregationWarningSummarizer.summarize(result.warnings()).stream()
                        .map(summary -> new EngineIntelligenceWarningSummary(
                                mapWarningCode(summary.code()),
                                summary.count()
                        ))
                        .toList()
        );
    }

    private EngineIntelligenceEngineResult mapEngineResult(NormalizedFraudEngineResult result) {
        FraudEngineStatus status = publicStatus(result);
        return new EngineIntelligenceEngineResult(
                result.engineId(),
                result.engineType(),
                status,
                publicRiskLevel(status, result.riskLevel()),
                EngineIntelligenceScoreBucket.from(status, result.score()),
                result.reasonCodes()
        );
    }

    private EngineIntelligenceComparison mapComparison(List<EngineIntelligenceEngineResult> engines) {
        EngineIntelligenceEngineResult rules = engine(engines, FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID);
        EngineIntelligenceEngineResult ml = engine(engines, FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID);
        if (rules == null || ml == null || rules.status() != FraudEngineStatus.AVAILABLE) {
            return operationalComparison(EngineIntelligenceAgreementStatus.REQUIRED_ENGINE_NOT_COMPARABLE);
        }
        if (ml.status() != FraudEngineStatus.AVAILABLE) {
            return operationalComparison(EngineIntelligenceAgreementStatus.PARTIAL);
        }
        EngineIntelligenceRiskMismatchStatus riskMismatch = riskMismatch(rules.riskLevel(), ml.riskLevel());
        return new EngineIntelligenceComparison(
                EngineIntelligenceComparisonType.RULES_VS_ML,
                FraudEngineIdentityContract.rulesVsMlComparisonEngineIds(),
                agreement(riskMismatch),
                riskMismatch,
                deltaBucket(rules.scoreBucket(), ml.scoreBucket())
        );
    }

    private EngineIntelligenceComparison operationalComparison(EngineIntelligenceAgreementStatus agreementStatus) {
        return new EngineIntelligenceComparison(
                EngineIntelligenceComparisonType.RULES_VS_ML,
                FraudEngineIdentityContract.rulesVsMlComparisonEngineIds(),
                agreementStatus,
                EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
        );
    }

    private EngineIntelligenceDiagnosticSignal mapDiagnosticSignal(FraudEngineStrongestSignal signal) {
        return new EngineIntelligenceDiagnosticSignal(
                signal.engineId(),
                signal.engineType(),
                signal.status(),
                mapSignalCategory(signal.signalCategory()),
                publicSignalRiskLevel(signal),
                publicSignalScoreBucket(signal),
                signal.reasonCode()
        );
    }

    private RiskLevel publicRiskLevel(FraudEngineStatus status, RiskLevel riskLevel) {
        return status == FraudEngineStatus.AVAILABLE ? riskLevel : null;
    }

    private FraudEngineStatus publicStatus(NormalizedFraudEngineResult result) {
        if (result.status() == FraudEngineStatus.AVAILABLE
                && (result.score() == null || result.riskLevel() == null)) {
            return FraudEngineStatus.DEGRADED;
        }
        return result.status();
    }

    private RiskLevel publicSignalRiskLevel(FraudEngineStrongestSignal signal) {
        return signal.signalCategory() == FraudEngineSignalCategory.OPERATIONAL_SIGNAL
                ? null
                : publicRiskLevel(signal.status(), signal.riskLevel());
    }

    private EngineIntelligenceScoreBucket publicSignalScoreBucket(FraudEngineStrongestSignal signal) {
        if (signal.signalCategory() == FraudEngineSignalCategory.OPERATIONAL_SIGNAL) {
            return EngineIntelligenceScoreBucket.UNAVAILABLE;
        }
        return EngineIntelligenceScoreBucket.from(signal.status(), signal.score());
    }

    private EngineIntelligenceSignalCategory mapSignalCategory(FraudEngineSignalCategory category) {
        return switch (category) {
            case FRAUD_SIGNAL -> EngineIntelligenceSignalCategory.FRAUD_SIGNAL;
            case OPERATIONAL_SIGNAL -> EngineIntelligenceSignalCategory.OPERATIONAL_SIGNAL;
        };
    }

    private EngineIntelligenceEngineResult engine(List<EngineIntelligenceEngineResult> engines, String engineId) {
        return engines.stream()
                .filter(engine -> engineId.equals(engine.engineId()))
                .findFirst()
                .orElse(null);
    }

    private EngineIntelligenceRiskMismatchStatus riskMismatch(RiskLevel rules, RiskLevel ml) {
        int distance = Math.abs(riskSeverity(rules) - riskSeverity(ml));
        if (distance == 0) {
            return EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL;
        }
        if (distance == 1) {
            return EngineIntelligenceRiskMismatchStatus.ADJACENT_RISK_LEVEL;
        }
        return EngineIntelligenceRiskMismatchStatus.MATERIAL_RISK_MISMATCH;
    }

    private EngineIntelligenceAgreementStatus agreement(EngineIntelligenceRiskMismatchStatus riskMismatch) {
        return switch (riskMismatch) {
            case SAME_RISK_LEVEL -> EngineIntelligenceAgreementStatus.AGREEMENT;
            case ADJACENT_RISK_LEVEL -> EngineIntelligenceAgreementStatus.ADJACENT_RISK_VARIANCE;
            case MATERIAL_RISK_MISMATCH -> EngineIntelligenceAgreementStatus.DISAGREEMENT;
            case NOT_COMPARABLE -> EngineIntelligenceAgreementStatus.REQUIRED_ENGINE_NOT_COMPARABLE;
        };
    }

    private EngineIntelligenceScoreDeltaBucket deltaBucket(
            EngineIntelligenceScoreBucket rules,
            EngineIntelligenceScoreBucket ml
    ) {
        if (rules == ml) {
            return EngineIntelligenceScoreDeltaBucket.NONE;
        }
        if ((rules == EngineIntelligenceScoreBucket.LOW && ml == EngineIntelligenceScoreBucket.VERY_HIGH)
                || (rules == EngineIntelligenceScoreBucket.VERY_HIGH && ml == EngineIntelligenceScoreBucket.LOW)) {
            return EngineIntelligenceScoreDeltaBucket.LARGE;
        }
        return EngineIntelligenceScoreDeltaBucket.SMALL;
    }

    private int riskSeverity(RiskLevel riskLevel) {
        return switch (riskLevel) {
            case LOW -> 1;
            case MEDIUM -> 2;
            case HIGH -> 3;
            case CRITICAL -> 4;
        };
    }

    private EngineIntelligenceWarningCode mapWarningCode(FraudEngineAggregationWarningCode code) {
        return switch (code) {
            case ENGINE_RESULT_LIMIT_APPLIED -> EngineIntelligenceWarningCode.ENGINE_RESULT_LIMIT_APPLIED;
            case REASON_CODE_NULL_DROPPED -> EngineIntelligenceWarningCode.REASON_CODE_NULL_DROPPED;
            case REASON_CODE_BLANK_DROPPED -> EngineIntelligenceWarningCode.REASON_CODE_BLANK_DROPPED;
            case REASON_CODE_UNSUPPORTED_DROPPED -> EngineIntelligenceWarningCode.REASON_CODE_UNSUPPORTED_DROPPED;
            case REASON_CODE_LIMIT_APPLIED -> EngineIntelligenceWarningCode.REASON_CODE_LIMIT_APPLIED;
            case EVIDENCE_LIMIT_APPLIED -> EngineIntelligenceWarningCode.EVIDENCE_LIMIT_APPLIED;
            case EVIDENCE_TEXT_TRUNCATED -> EngineIntelligenceWarningCode.EVIDENCE_TEXT_TRUNCATED;
            case EVIDENCE_UNSAFE_DROPPED -> EngineIntelligenceWarningCode.EVIDENCE_UNSAFE_DROPPED;
            case EVIDENCE_UNSUPPORTED_REASON_CODE_DROPPED ->
                    EngineIntelligenceWarningCode.EVIDENCE_UNSUPPORTED_REASON_CODE_DROPPED;
            case CONTRIBUTION_LIMIT_APPLIED -> EngineIntelligenceWarningCode.CONTRIBUTION_LIMIT_APPLIED;
            case CONTRIBUTION_TEXT_TRUNCATED -> EngineIntelligenceWarningCode.CONTRIBUTION_TEXT_TRUNCATED;
            case CONTRIBUTION_UNSAFE_DROPPED -> EngineIntelligenceWarningCode.CONTRIBUTION_UNSAFE_DROPPED;
            case CONTRIBUTION_VALUE_DROPPED -> EngineIntelligenceWarningCode.CONTRIBUTION_VALUE_DROPPED;
        };
    }
}
