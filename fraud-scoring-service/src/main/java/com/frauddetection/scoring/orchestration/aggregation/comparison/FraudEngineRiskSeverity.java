package com.frauddetection.scoring.orchestration.aggregation.comparison;

import com.frauddetection.common.events.enums.RiskLevel;

public final class FraudEngineRiskSeverity {

    private FraudEngineRiskSeverity() {
    }

    public static int rank(RiskLevel riskLevel) {
        if (riskLevel == null) {
            return -1;
        }
        return switch (riskLevel) {
            case LOW -> 1;
            case MEDIUM -> 2;
            case HIGH -> 3;
            case CRITICAL -> 4;
        };
    }

    public static int distance(RiskLevel first, RiskLevel second) {
        if (first == null || second == null) {
            throw new IllegalArgumentException("AGGREGATION_RISK_LEVEL_REQUIRED");
        }
        return Math.abs(rank(first) - rank(second));
    }
}
