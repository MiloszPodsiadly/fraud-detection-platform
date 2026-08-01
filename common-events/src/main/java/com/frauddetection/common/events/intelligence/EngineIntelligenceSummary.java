package com.frauddetection.common.events.intelligence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EngineIntelligenceSummary(
        int contractVersion,
        Instant generatedAt,
        List<EngineIntelligenceEngineResult> engines,
        EngineIntelligenceComparison comparison,
        List<EngineIntelligenceDiagnosticSignal> diagnosticSignals,
        List<EngineIntelligenceWarningSummary> warnings
) {
    public static final int CONTRACT_VERSION = 1;

    @JsonCreator
    public static EngineIntelligenceSummary fromJson(
            @JsonProperty("contractVersion") Integer contractVersion,
            @JsonProperty("generatedAt") Instant generatedAt,
            @JsonProperty("engines") List<EngineIntelligenceEngineResult> engines,
            @JsonProperty("comparison") EngineIntelligenceComparison comparison,
            @JsonProperty("diagnosticSignals") List<EngineIntelligenceDiagnosticSignal> diagnosticSignals,
            @JsonProperty("warnings") List<EngineIntelligenceWarningSummary> warnings
    ) {
        return new EngineIntelligenceSummary(
                contractVersion == null ? 0 : contractVersion,
                generatedAt,
                engines,
                comparison,
                diagnosticSignals,
                warnings
        );
    }

    public EngineIntelligenceSummary {
        if (contractVersion != CONTRACT_VERSION) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_UNSUPPORTED_CONTRACT_VERSION");
        }
        Objects.requireNonNull(generatedAt, "generatedAt is required");
        engines = EngineIntelligenceValuePolicy.copyBounded(
                engines,
                EngineIntelligenceValuePolicy.MAX_ENGINES,
                "engines"
        );
        requireUniqueEngineIds(engines);
        requireCanonicalEngineOrder(engines);
        Objects.requireNonNull(comparison, "comparison is required");
        diagnosticSignals = EngineIntelligenceValuePolicy.copyBounded(
                diagnosticSignals,
                EngineIntelligenceValuePolicy.MAX_DIAGNOSTIC_SIGNALS,
                "diagnosticSignals"
        );
        warnings = EngineIntelligenceValuePolicy.copyBounded(
                warnings,
                EngineIntelligenceValuePolicy.MAX_WARNINGS,
                "warnings"
        );
    }

    private static void requireUniqueEngineIds(List<EngineIntelligenceEngineResult> engines) {
        Set<String> engineIds = new HashSet<>();
        for (EngineIntelligenceEngineResult engine : engines) {
            if (!engineIds.add(engine.engineId())) {
                throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DUPLICATE_ENGINE_ID");
            }
        }
    }

    private static void requireCanonicalEngineOrder(List<EngineIntelligenceEngineResult> engines) {
        int previousOrder = -1;
        for (EngineIntelligenceEngineResult engine : engines) {
            int currentOrder = com.frauddetection.common.events.engine.FraudEngineIdentityContract.orderOf(engine.engineId());
            if (currentOrder <= previousOrder) {
                throw new IllegalArgumentException("ENGINE_INTELLIGENCE_ENGINE_ORDER_INVALID");
            }
            previousOrder = currentOrder;
        }
    }
}
