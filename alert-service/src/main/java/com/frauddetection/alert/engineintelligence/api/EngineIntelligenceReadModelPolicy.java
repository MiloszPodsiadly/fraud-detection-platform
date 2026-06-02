package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceDiagnosticSignalProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceEngineProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionPolicy;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceWarningProjection;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.events.intelligence.EngineIntelligenceDiagnosticSignal;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningSummary;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

final class EngineIntelligenceReadModelPolicy {

    private static final Pattern TRANSACTION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]+$");

    private final EngineIntelligenceProjectionPolicy projectionPolicy;

    EngineIntelligenceReadModelPolicy() {
        this(new EngineIntelligenceProjectionPolicy());
    }

    EngineIntelligenceReadModelPolicy(EngineIntelligenceProjectionPolicy projectionPolicy) {
        this.projectionPolicy = Objects.requireNonNull(projectionPolicy, "projectionPolicy is required");
    }

    void validate(EngineIntelligenceProjection projection) {
        // FDP-96 revalidates stored FDP-95 projections before API exposure as defense-in-depth against stale
        // or corrupted Mongo documents. Reusing the FDP-92/FDP-95 public/projection path avoids a third enum
        // and reason-code source of truth.
        EngineIntelligenceProjection source = Objects.requireNonNull(projection, "projection is required");
        String transactionId = projectionPolicy.validatedTransactionId(source.getTransactionId());
        if (!TRANSACTION_ID_PATTERN.matcher(transactionId).matches()) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_TRANSACTION_ID_INVALID");
        }

        projectionPolicy.validatedCopy(new EngineIntelligenceSummary(
                source.getContractVersion(),
                source.getGeneratedAt(),
                bounded(source.getEngines(), EngineIntelligenceProjectionPolicy.MAX_ENGINES).stream()
                        .map(this::engine)
                        .toList(),
                new EngineIntelligenceComparison(
                        source.getComparisonStatus(),
                        source.getRiskMismatchStatus(),
                        source.getScoreDeltaBucket()
                ),
                bounded(source.getDiagnosticSignals(), EngineIntelligenceProjectionPolicy.MAX_DIAGNOSTIC_SIGNALS)
                        .stream()
                        .map(this::diagnosticSignal)
                        .toList(),
                bounded(source.getWarnings(), EngineIntelligenceProjectionPolicy.MAX_WARNINGS).stream()
                        .map(this::warning)
                        .toList()
        ));
    }

    private EngineIntelligenceEngineResult engine(EngineIntelligenceEngineProjection source) {
        Objects.requireNonNull(source, "engine summary is required");
        return new EngineIntelligenceEngineResult(
                source.engineId(),
                source.engineType(),
                source.status(),
                source.riskLevel(),
                source.scoreBucket(),
                bounded(source.reasonCodes(), EngineIntelligenceProjectionPolicy.MAX_REASON_CODES_PER_ENGINE)
        );
    }

    private EngineIntelligenceDiagnosticSignal diagnosticSignal(EngineIntelligenceDiagnosticSignalProjection source) {
        Objects.requireNonNull(source, "diagnostic signal is required");
        return new EngineIntelligenceDiagnosticSignal(
                source.engineId(),
                source.engineType(),
                source.engineStatus(),
                source.signalCategory(),
                source.riskLevel(),
                source.scoreBucket(),
                source.reasonCode()
        );
    }

    private EngineIntelligenceWarningSummary warning(EngineIntelligenceWarningProjection source) {
        Objects.requireNonNull(source, "warning is required");
        return new EngineIntelligenceWarningSummary(source.warningCode(), source.count());
    }

    private <T> List<T> bounded(List<T> values, int maximum) {
        List<T> safeValues = List.copyOf(Objects.requireNonNull(values, "bounded values are required"));
        if (safeValues.size() > maximum) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_READ_MODEL_LIMIT_EXCEEDED");
        }
        return safeValues;
    }
}
