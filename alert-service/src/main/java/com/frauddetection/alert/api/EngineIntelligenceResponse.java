package com.frauddetection.alert.api;

import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.events.intelligence.EngineIntelligenceDiagnosticSignal;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningSummary;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record EngineIntelligenceResponse(
        EngineIntelligenceResponseStatus status,
        Integer contractVersion,
        Instant generatedAt,
        EngineIntelligenceComparisonResponse comparison,
        List<EngineIntelligenceEngineResponse> engines,
        List<EngineIntelligenceDiagnosticSignalResponse> diagnosticSignals,
        List<EngineIntelligenceWarningResponse> warnings
) {

    public EngineIntelligenceResponse {
        status = Objects.requireNonNull(status, "status is required");
        engines = immutable(engines);
        diagnosticSignals = immutable(diagnosticSignals);
        warnings = immutable(warnings);
        if (status == EngineIntelligenceResponseStatus.ABSENT || status == EngineIntelligenceResponseStatus.UNAVAILABLE) {
            requireEmptyUnavailableEnvelope(contractVersion, generatedAt, comparison, engines, diagnosticSignals, warnings);
        } else {
            validateProjectedEnvelope(contractVersion, generatedAt, comparison, engines, diagnosticSignals, warnings);
        }
    }

    public static EngineIntelligenceResponse absent() {
        return new EngineIntelligenceResponse(
                EngineIntelligenceResponseStatus.ABSENT,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of()
        );
    }

    public static EngineIntelligenceResponse unavailable() {
        return new EngineIntelligenceResponse(
                EngineIntelligenceResponseStatus.UNAVAILABLE,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static void requireEmptyUnavailableEnvelope(
            Integer contractVersion,
            Instant generatedAt,
            EngineIntelligenceComparisonResponse comparison,
            List<EngineIntelligenceEngineResponse> engines,
            List<EngineIntelligenceDiagnosticSignalResponse> diagnosticSignals,
            List<EngineIntelligenceWarningResponse> warnings
    ) {
        if (contractVersion != null || generatedAt != null || comparison != null
                || !engines.isEmpty() || !diagnosticSignals.isEmpty() || !warnings.isEmpty()) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_UNAVAILABLE_ENVELOPE_MUST_BE_EMPTY");
        }
    }

    private static void validateProjectedEnvelope(
            Integer contractVersion,
            Instant generatedAt,
            EngineIntelligenceComparisonResponse comparison,
            List<EngineIntelligenceEngineResponse> engines,
            List<EngineIntelligenceDiagnosticSignalResponse> diagnosticSignals,
            List<EngineIntelligenceWarningResponse> warnings
    ) {
        new EngineIntelligenceSummary(
                Objects.requireNonNull(contractVersion, "contractVersion is required"),
                Objects.requireNonNull(generatedAt, "generatedAt is required"),
                engines.stream()
                        .map(EngineIntelligenceResponse::engineResult)
                        .toList(),
                comparison(comparison),
                diagnosticSignals.stream()
                        .map(EngineIntelligenceResponse::diagnosticSignal)
                        .toList(),
                warnings.stream()
                        .map(warning -> new EngineIntelligenceWarningSummary(warning.warningCode(), warning.count()))
                        .toList()
        );
    }

    private static EngineIntelligenceEngineResult engineResult(EngineIntelligenceEngineResponse engine) {
        Objects.requireNonNull(engine, "engines must not contain null entries");
        return new EngineIntelligenceEngineResult(
                engine.engineId(),
                engine.engineType(),
                engine.status().toFraudEngineStatus(),
                engine.riskLevel(),
                engine.scoreBucket(),
                engine.reasonCodes()
        );
    }

    private static EngineIntelligenceDiagnosticSignal diagnosticSignal(EngineIntelligenceDiagnosticSignalResponse signal) {
        Objects.requireNonNull(signal, "diagnosticSignals must not contain null entries");
        return new EngineIntelligenceDiagnosticSignal(
                signal.engineId(),
                signal.engineType(),
                signal.engineStatus().toFraudEngineStatus(),
                signal.signalCategory(),
                signal.riskLevel(),
                signal.scoreBucket(),
                signal.reasonCode()
        );
    }

    private static EngineIntelligenceComparison comparison(EngineIntelligenceComparisonResponse comparison) {
        Objects.requireNonNull(comparison, "comparison is required");
        return new EngineIntelligenceComparison(
                comparison.comparisonType(),
                comparison.comparedEngineIds(),
                comparison.agreementStatus(),
                comparison.riskMismatchStatus(),
                comparison.scoreDeltaBucket()
        );
    }
}
