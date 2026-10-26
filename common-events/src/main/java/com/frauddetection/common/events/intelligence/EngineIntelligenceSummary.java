package com.frauddetection.common.events.intelligence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    /** Current producer version. V2 is reader-ready only until consumer migration and rollout. */
    public static final int CONTRACT_VERSION = EngineIntelligenceVersionedIdentity.V1;
    public static final int DEVICE_CONTRACT_VERSION = EngineIntelligenceVersionedIdentity.V2;

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
        if (!EngineIntelligenceVersionedIdentity.supportedVersion(contractVersion)) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_UNSUPPORTED_CONTRACT_VERSION");
        }
        Objects.requireNonNull(generatedAt, "generatedAt is required");
        engines = EngineIntelligenceValuePolicy.copyBounded(
                engines,
                EngineIntelligenceVersionedIdentity.maxEngines(contractVersion),
                "engines"
        );
        requireUniqueEngineIds(engines);
        requireCanonicalEngineOrder(engines, contractVersion);
        requireVersionedEngineSemantics(engines, contractVersion);
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
        EngineIntelligenceSummarySemanticPolicy.validate(contractVersion, engines, comparison, diagnosticSignals);
    }

    private static void requireUniqueEngineIds(List<EngineIntelligenceEngineResult> engines) {
        Set<String> engineIds = new HashSet<>();
        for (EngineIntelligenceEngineResult engine : engines) {
            if (!engineIds.add(engine.engineId())) {
                throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DUPLICATE_ENGINE_ID");
            }
        }
    }

    private static void requireCanonicalEngineOrder(List<EngineIntelligenceEngineResult> engines, int version) {
        List<String> order = EngineIntelligenceVersionedIdentity.engineOrder(version);
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

    private static void requireVersionedEngineSemantics(List<EngineIntelligenceEngineResult> engines, int version) {
        for (EngineIntelligenceEngineResult engine : engines) {
            boolean device = EngineIntelligenceVersionedIdentity.DEVICE_PRIMARY_ENGINE_ID.equals(engine.engineId());
            if (device && engine.status() == FraudEngineStatus.AVAILABLE) {
                throw new IllegalArgumentException("ENGINE_INTELLIGENCE_DEVICE_SCORE_UNCALIBRATED");
            }
            for (String code : engine.reasonCodes()) {
                if (device && (version != DEVICE_CONTRACT_VERSION
                        || !EngineIntelligenceValuePolicy.allowedForDevice(code))
                        || !device && EngineIntelligenceValuePolicy.isDeviceV2Reason(code)) {
                    throw new IllegalArgumentException("ENGINE_INTELLIGENCE_REASON_CODE_NOT_ALLOWED_FOR_ENGINE");
                }
            }
        }
    }
}
