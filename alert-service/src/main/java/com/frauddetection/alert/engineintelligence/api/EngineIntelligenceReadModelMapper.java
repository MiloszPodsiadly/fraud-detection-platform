package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceDiagnosticSignalProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceEngineProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionPolicy;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceWarningProjection;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public class EngineIntelligenceReadModelMapper {

    public EngineIntelligenceReadModel map(EngineIntelligenceProjection projection) {
        EngineIntelligenceProjection source = Objects.requireNonNull(projection, "projection is required");
        List<EngineIntelligenceEngineReadModel> engines = bounded(
                source.getEngines(),
                EngineIntelligenceProjectionPolicy.MAX_ENGINES,
                "engine summaries"
        ).stream().map(this::mapEngine).toList();
        List<EngineIntelligenceDiagnosticSignalReadModel> diagnosticSignals = bounded(
                source.getDiagnosticSignals(),
                EngineIntelligenceProjectionPolicy.MAX_DIAGNOSTIC_SIGNALS,
                "diagnostic signals"
        ).stream().map(this::mapDiagnosticSignal).toList();
        List<EngineIntelligenceWarningReadModel> warnings = bounded(
                source.getWarnings(),
                EngineIntelligenceProjectionPolicy.MAX_WARNINGS,
                "warnings"
        ).stream().map(this::mapWarning).toList();

        return EngineIntelligenceReadModel.projected(
                source.getTransactionId(),
                source.getContractVersion(),
                source.getGeneratedAt(),
                new EngineIntelligenceComparisonReadModel(
                        source.getComparisonStatus(),
                        source.getRiskMismatchStatus(),
                        source.getScoreDeltaBucket()
                ),
                engines,
                diagnosticSignals,
                warnings
        );
    }

    private EngineIntelligenceEngineReadModel mapEngine(EngineIntelligenceEngineProjection engine) {
        return new EngineIntelligenceEngineReadModel(
                engine.engineId(),
                engine.engineType(),
                engine.status(),
                engine.riskLevel(),
                engine.scoreBucket(),
                bounded(
                        engine.reasonCodes(),
                        EngineIntelligenceProjectionPolicy.MAX_REASON_CODES_PER_ENGINE,
                        "engine reason codes"
                )
        );
    }

    private EngineIntelligenceDiagnosticSignalReadModel mapDiagnosticSignal(
            EngineIntelligenceDiagnosticSignalProjection signal
    ) {
        return new EngineIntelligenceDiagnosticSignalReadModel(
                signal.engineId(),
                signal.engineType(),
                signal.engineStatus(),
                signal.signalCategory(),
                signal.riskLevel(),
                signal.scoreBucket(),
                signal.reasonCode()
        );
    }

    private EngineIntelligenceWarningReadModel mapWarning(EngineIntelligenceWarningProjection warning) {
        return new EngineIntelligenceWarningReadModel(warning.warningCode(), warning.count());
    }

    private <T> List<T> bounded(List<T> values, int maximum, String field) {
        List<T> safeValues = List.copyOf(Objects.requireNonNull(values, field + " are required"));
        if (safeValues.size() > maximum) {
            throw new IllegalStateException(field + " exceed the bounded API limit");
        }
        return safeValues;
    }
}
