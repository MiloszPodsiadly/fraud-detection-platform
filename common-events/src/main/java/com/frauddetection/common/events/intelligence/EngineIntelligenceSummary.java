package com.frauddetection.common.events.intelligence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineStatus;

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
    public static final int CONTRACT_VERSION = FraudEngineIdentityContract.ENGINE_INTELLIGENCE_CONTRACT_VERSION;

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
        FraudEngineIdentityContract.requireSupportedEngineIntelligenceVersion(contractVersion);
        Objects.requireNonNull(generatedAt, "generatedAt is required");
        engines = EngineIntelligenceValuePolicy.copyBounded(
                engines,
                FraudEngineIdentityContract.maxEngineIntelligenceEngines(),
                "engines"
        );
        requireUniqueEngineIds(engines);
        requireCanonicalEngineOrder(engines);
        requireEngineSemantics(engines);
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
        EngineIntelligenceSummarySemanticPolicy.validate(engines, comparison, diagnosticSignals);
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
        List<String> order = FraudEngineIdentityContract.engineOrder();
        int previousOrder = -1;
        for (EngineIntelligenceEngineResult engine : engines) {
            int currentOrder = order.indexOf(engine.engineId());
            if (currentOrder < 0) {
                throw new IllegalArgumentException("ENGINE_INTELLIGENCE_UNKNOWN_ENGINE_ID");
            }
            if (currentOrder <= previousOrder) {
                throw new IllegalArgumentException("ENGINE_INTELLIGENCE_ENGINE_ORDER_INVALID");
            }
            previousOrder = currentOrder;
        }
    }

    private static void requireEngineSemantics(List<EngineIntelligenceEngineResult> engines) {
        for (EngineIntelligenceEngineResult engine : engines) {
            boolean device = FraudEngineIdentityContract.DEVICE_PRIMARY_ENGINE_ID.equals(engine.engineId());
            if (device && engine.status() == FraudEngineStatus.AVAILABLE) {
                throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DEVICE_SCORE_UNCALIBRATED");
            }
            for (String code : engine.reasonCodes()) {
                if (device && !EngineIntelligenceValuePolicy.allowedForDevice(code)
                        || !device && EngineIntelligenceValuePolicy.isDeviceReason(code)) {
                    throw new IllegalArgumentException("ENGINE_INTELLIGENCE_REASON_CODE_NOT_ALLOWED_FOR_ENGINE");
                }
            }
        }
    }
}
