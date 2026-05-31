package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineStatus;

import java.util.List;

public final class FraudEngineRiskMismatchCalculator {

    public FraudEngineRiskMismatch calculate(List<NormalizedFraudEngineResult> results) {
        if (results == null || results.size() < 2) {
            return mismatch(FraudEngineRiskMismatchStatus.NOT_COMPARABLE);
        }
        NormalizedFraudEngineResult rules = resultFor(results, "rules.primary");
        NormalizedFraudEngineResult ml = resultFor(results, "ml.python.primary");
        if (rules == null
                || ml == null
                || rules.status() != FraudEngineStatus.AVAILABLE
                || ml.status() != FraudEngineStatus.AVAILABLE
                || rules.riskLevel() == null
                || ml.riskLevel() == null) {
            return mismatch(FraudEngineRiskMismatchStatus.NOT_COMPARABLE);
        }
        int distance = FraudEngineRiskSeverity.distance(rules.riskLevel(), ml.riskLevel());
        if (distance == 0) {
            return mismatch(FraudEngineRiskMismatchStatus.SAME_RISK_LEVEL);
        }
        if (distance == 1) {
            return mismatch(FraudEngineRiskMismatchStatus.ADJACENT_RISK_LEVEL);
        }
        return mismatch(FraudEngineRiskMismatchStatus.MATERIAL_RISK_MISMATCH);
    }

    private FraudEngineRiskMismatch mismatch(FraudEngineRiskMismatchStatus status) {
        return new FraudEngineRiskMismatch(status);
    }

    private NormalizedFraudEngineResult resultFor(List<NormalizedFraudEngineResult> results, String engineId) {
        return results.stream()
                .filter(result -> engineId.equals(result.engineId()))
                .findFirst()
                .orElse(null);
    }
}
