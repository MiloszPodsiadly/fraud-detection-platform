package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineStatus;
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

import java.util.Objects;

public final class PublicEngineIntelligenceMapper {

    public EngineIntelligenceSummary map(FraudEngineAggregationResult result) {
        Objects.requireNonNull(result, "result is required");
        return new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                result.generatedAt(),
                result.normalizedEngineResults().stream().map(this::mapEngineResult).toList(),
                mapComparison(result),
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
        return new EngineIntelligenceEngineResult(
                result.engineId(),
                result.engineType(),
                result.status(),
                publicRiskLevel(result.status(), result.riskLevel()),
                EngineIntelligenceScoreBucket.from(result.status(), result.score()),
                result.reasonCodes()
        );
    }

    private EngineIntelligenceComparison mapComparison(FraudEngineAggregationResult result) {
        return new EngineIntelligenceComparison(
                EngineIntelligenceAgreementStatus.valueOf(result.agreementStatus().name()),
                EngineIntelligenceRiskMismatchStatus.valueOf(result.riskMismatch().status().name()),
                result.scoreDelta().status() == FraudEngineScoreDeltaStatus.AVAILABLE
                        ? EngineIntelligenceScoreDeltaBucket.fromComparableDelta(result.scoreDelta().absoluteDelta())
                        : EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
        );
    }

    private EngineIntelligenceDiagnosticSignal mapDiagnosticSignal(FraudEngineStrongestSignal signal) {
        return new EngineIntelligenceDiagnosticSignal(
                signal.engineId(),
                signal.engineType(),
                signal.status(),
                EngineIntelligenceSignalCategory.valueOf(signal.signalCategory().name()),
                publicSignalRiskLevel(signal),
                publicSignalScoreBucket(signal),
                signal.reasonCode()
        );
    }

    private RiskLevel publicRiskLevel(FraudEngineStatus status, RiskLevel riskLevel) {
        return status == FraudEngineStatus.AVAILABLE ? riskLevel : null;
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
