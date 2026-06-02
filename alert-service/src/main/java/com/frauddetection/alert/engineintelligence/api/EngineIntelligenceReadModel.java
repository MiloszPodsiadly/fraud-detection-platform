package com.frauddetection.alert.engineintelligence.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EngineIntelligenceReadModel(
        String transactionId,
        boolean available,
        String reason,
        Integer contractVersion,
        Instant generatedAt,
        EngineIntelligenceComparisonReadModel comparison,
        Integer engineCount,
        Integer diagnosticSignalCount,
        Integer warningCount,
        List<EngineIntelligenceEngineReadModel> engines,
        List<EngineIntelligenceDiagnosticSignalReadModel> diagnosticSignals,
        List<EngineIntelligenceWarningReadModel> warnings
) {

    public static EngineIntelligenceReadModel projected(
            String transactionId,
            int contractVersion,
            Instant generatedAt,
            EngineIntelligenceComparisonReadModel comparison,
            List<EngineIntelligenceEngineReadModel> engines,
            List<EngineIntelligenceDiagnosticSignalReadModel> diagnosticSignals,
            List<EngineIntelligenceWarningReadModel> warnings
    ) {
        List<EngineIntelligenceEngineReadModel> boundedEngines = immutableList(engines);
        List<EngineIntelligenceDiagnosticSignalReadModel> boundedSignals = immutableList(diagnosticSignals);
        List<EngineIntelligenceWarningReadModel> boundedWarnings = immutableList(warnings);
        return new EngineIntelligenceReadModel(
                transactionId,
                true,
                null,
                contractVersion,
                generatedAt,
                comparison,
                boundedEngines.size(),
                boundedSignals.size(),
                boundedWarnings.size(),
                boundedEngines,
                boundedSignals,
                boundedWarnings
        );
    }

    public static EngineIntelligenceReadModel notProjected(String transactionId) {
        return new EngineIntelligenceReadModel(
                transactionId,
                false,
                "NOT_PROJECTED",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static <T> List<T> immutableList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
