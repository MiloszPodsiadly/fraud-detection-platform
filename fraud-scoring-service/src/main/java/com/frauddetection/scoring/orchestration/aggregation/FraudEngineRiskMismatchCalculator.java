package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineStatus;

import java.util.List;

public final class FraudEngineRiskMismatchCalculator {

    public FraudEngineRiskMismatch calculate(List<NormalizedFraudEngineResult> results) {
        if (results == null || results.size() < 2) {
            return mismatch(FraudEngineRiskMismatchStatus.NOT_COMPARABLE);
        }
        NormalizedFraudEngineResult first = results.get(0);
        NormalizedFraudEngineResult second = results.get(1);
        if (first.status() != FraudEngineStatus.AVAILABLE
                || second.status() != FraudEngineStatus.AVAILABLE
                || first.riskLevel() == null
                || second.riskLevel() == null) {
            return mismatch(FraudEngineRiskMismatchStatus.NOT_COMPARABLE);
        }
        int distance = Math.abs(first.riskLevel().ordinal() - second.riskLevel().ordinal());
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
}
