package com.frauddetection.common.events.intelligence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EngineIntelligenceComparison(
        EngineIntelligenceComparisonType comparisonType,
        List<String> comparedEngineIds,
        EngineIntelligenceAgreementStatus agreementStatus,
        EngineIntelligenceRiskMismatchStatus riskMismatchStatus,
        EngineIntelligenceScoreDeltaBucket scoreDeltaBucket
) {
    @JsonCreator
    public static EngineIntelligenceComparison fromJson(
            @JsonProperty("comparisonType") EngineIntelligenceComparisonType comparisonType,
            @JsonProperty("comparedEngineIds") List<String> comparedEngineIds,
            @JsonProperty("agreementStatus") EngineIntelligenceAgreementStatus agreementStatus,
            @JsonProperty("riskMismatchStatus") EngineIntelligenceRiskMismatchStatus riskMismatchStatus,
            @JsonProperty("scoreDeltaBucket") EngineIntelligenceScoreDeltaBucket scoreDeltaBucket
    ) {
        return new EngineIntelligenceComparison(
                comparisonType,
                comparedEngineIds,
                agreementStatus,
                riskMismatchStatus,
                scoreDeltaBucket
        );
    }

    public EngineIntelligenceComparison(
            EngineIntelligenceAgreementStatus agreementStatus,
            EngineIntelligenceRiskMismatchStatus riskMismatchStatus,
            EngineIntelligenceScoreDeltaBucket scoreDeltaBucket
    ) {
        this(
                EngineIntelligenceComparisonType.RULES_VS_ML,
                FraudEngineIdentityContract.rulesVsMlComparisonEngineIds(),
                agreementStatus,
                riskMismatchStatus,
                scoreDeltaBucket
        );
    }

    public EngineIntelligenceComparison {
        Objects.requireNonNull(comparisonType, "comparisonType is required");
        if (comparisonType != EngineIntelligenceComparisonType.RULES_VS_ML) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_COMPARISON_TYPE_UNSUPPORTED");
        }
        Objects.requireNonNull(comparedEngineIds, "comparedEngineIds is required");
        comparedEngineIds = List.copyOf(comparedEngineIds);
        if (!comparedEngineIds.equals(FraudEngineIdentityContract.rulesVsMlComparisonEngineIds())) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_COMPARISON_ENGINE_IDS_INVALID");
        }
        Objects.requireNonNull(agreementStatus, "agreementStatus is required");
        Objects.requireNonNull(riskMismatchStatus, "riskMismatchStatus is required");
        Objects.requireNonNull(scoreDeltaBucket, "scoreDeltaBucket is required");
    }
}
