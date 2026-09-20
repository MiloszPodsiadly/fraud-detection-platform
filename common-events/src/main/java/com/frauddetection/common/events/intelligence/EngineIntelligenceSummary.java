package com.frauddetection.common.events.intelligence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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
    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();

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

    public static EngineIntelligenceSummary fromHistoricalV1JsonNode(JsonNode node) {
        return fromJsonNode(node, true);
    }

    public static EngineIntelligenceSummary fromCurrentJsonNode(JsonNode node) {
        return fromJsonNode(node, false);
    }

    private static EngineIntelligenceSummary fromJsonNode(JsonNode node, boolean historicalV1) {
        if (node == null || node.isNull()) {
            return null;
        }
        return new EngineIntelligenceSummary(
                integer(node.get("contractVersion")),
                value(node.get("generatedAt"), Instant.class),
                list(node.get("engines"), new TypeReference<List<EngineIntelligenceEngineResult>>() {
                }),
                comparison(node.get("comparison"), historicalV1),
                list(node.get("diagnosticSignals"), new TypeReference<List<EngineIntelligenceDiagnosticSignal>>() {
                }),
                list(node.get("warnings"), new TypeReference<List<EngineIntelligenceWarningSummary>>() {
                })
        );
    }

    private static EngineIntelligenceComparison comparison(JsonNode node, boolean historicalV1) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!historicalV1) {
            return value(node, EngineIntelligenceComparison.class);
        }
        EngineIntelligenceComparisonV1Compatibility.NormalizedComparisonIdentity identity =
                EngineIntelligenceComparisonV1Compatibility.normalizeLegacyV1Identity(
                        enumValue(node.get("comparisonType"), EngineIntelligenceComparisonType.class),
                        listOrNull(node.get("comparedEngineIds"), new TypeReference<List<String>>() {
                        })
                );
        return new EngineIntelligenceComparison(
                identity.comparisonType(),
                identity.comparedEngineIds(),
                enumValue(node.get("agreementStatus"), EngineIntelligenceAgreementStatus.class),
                enumValue(node.get("riskMismatchStatus"), EngineIntelligenceRiskMismatchStatus.class),
                enumValue(node.get("scoreDeltaBucket"), EngineIntelligenceScoreDeltaBucket.class)
        );
    }

    private static Integer integer(JsonNode node) {
        return node == null || node.isNull() ? null : node.asInt();
    }

    private static <T> T value(JsonNode node, Class<T> type) {
        if (node == null || node.isNull()) {
            return null;
        }
        return JSON.treeToValue(node, type);
    }

    private static <T> List<T> list(JsonNode node, TypeReference<List<T>> type) {
        List<T> value = listOrNull(node, type);
        return value == null ? null : value;
    }

    private static <T> List<T> listOrNull(JsonNode node, TypeReference<List<T>> type) {
        if (node == null || node.isNull()) {
            return null;
        }
        return JSON.convertValue(node, type);
    }

    private static <E extends Enum<E>> E enumValue(JsonNode node, Class<E> type) {
        if (node == null || node.isNull()) {
            return null;
        }
        return Enum.valueOf(type, node.textValue());
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
