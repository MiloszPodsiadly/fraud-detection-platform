package com.frauddetection.alert.api;

import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;

import java.util.List;

public record EngineIntelligenceComparisonResponse(
        EngineIntelligenceComparisonType comparisonType,
        List<String> comparedEngineIds,
        EngineIntelligenceAgreementStatus agreementStatus,
        EngineIntelligenceRiskMismatchStatus riskMismatchStatus,
        EngineIntelligenceScoreDeltaBucket scoreDeltaBucket
) {
    public EngineIntelligenceComparisonResponse(
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
}
