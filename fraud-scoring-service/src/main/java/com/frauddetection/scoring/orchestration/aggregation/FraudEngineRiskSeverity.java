package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.enums.RiskLevel;

final class FraudEngineRiskSeverity {

    private FraudEngineRiskSeverity() {
    }

    static int rank(RiskLevel riskLevel) {
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

    static int distance(RiskLevel first, RiskLevel second) {
        if (first == null || second == null) {
            throw new IllegalArgumentException("AGGREGATION_RISK_LEVEL_REQUIRED");
        }
        return Math.abs(rank(first) - rank(second));
    }
}
