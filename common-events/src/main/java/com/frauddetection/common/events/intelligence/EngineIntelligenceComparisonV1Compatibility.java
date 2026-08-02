package com.frauddetection.common.events.intelligence;

import com.frauddetection.common.events.engine.FraudEngineIdentityContract;

import java.util.List;

final class EngineIntelligenceComparisonV1Compatibility {

    private EngineIntelligenceComparisonV1Compatibility() {
    }

    static NormalizedComparisonIdentity normalizeLegacyV1Identity(
            EngineIntelligenceComparisonType comparisonType,
            List<String> comparedEngineIds
    ) {
        if (comparisonType == null && comparedEngineIds == null) {
            return new NormalizedComparisonIdentity(
                    EngineIntelligenceComparisonType.RULES_VS_ML,
                    FraudEngineIdentityContract.rulesVsMlComparisonEngineIds()
            );
        }
        if (comparisonType == null || comparedEngineIds == null) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_COMPARISON_IDENTITY_INCOMPLETE");
        }
        return new NormalizedComparisonIdentity(comparisonType, comparedEngineIds);
    }

    record NormalizedComparisonIdentity(
            EngineIntelligenceComparisonType comparisonType,
            List<String> comparedEngineIds
    ) {
    }
}
