package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;

import java.util.List;

public record EngineIntelligenceComparisonReadModel(
        EngineIntelligenceComparisonType comparisonType,
        List<String> comparedEngineIds,
        EngineIntelligenceAgreementStatus agreementStatus,
        EngineIntelligenceRiskMismatchStatus riskMismatchStatus,
        EngineIntelligenceScoreDeltaBucket scoreDeltaBucket
) {
    public EngineIntelligenceComparisonReadModel {
        EngineIntelligenceComparison comparison = new EngineIntelligenceComparison(
                comparisonType,
                comparedEngineIds,
                agreementStatus,
                riskMismatchStatus,
                scoreDeltaBucket
        );
        comparisonType = comparison.comparisonType();
        comparedEngineIds = comparison.comparedEngineIds();
    }
}
