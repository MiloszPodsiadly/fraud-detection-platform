package com.frauddetection.alert.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
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
        engines = immutable(engines);
        diagnosticSignals = immutable(diagnosticSignals);
        warnings = immutable(warnings);
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
}
