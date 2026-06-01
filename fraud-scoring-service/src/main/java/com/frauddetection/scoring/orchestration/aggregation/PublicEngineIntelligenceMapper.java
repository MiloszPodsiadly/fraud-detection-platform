package com.frauddetection.scoring.orchestration.aggregation;

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
                                EngineIntelligenceWarningCode.valueOf(summary.code().name()),
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
                result.riskLevel(),
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
                signal.riskLevel(),
                EngineIntelligenceScoreBucket.from(signal.status(), signal.score()),
                signal.reasonCode()
        );
    }
}
