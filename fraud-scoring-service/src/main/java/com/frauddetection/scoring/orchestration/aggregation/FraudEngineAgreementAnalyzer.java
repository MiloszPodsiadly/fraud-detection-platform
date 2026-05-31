package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineStatus;

import java.util.List;

public final class FraudEngineAgreementAnalyzer {

    public FraudEngineAgreementStatus analyze(List<NormalizedFraudEngineResult> results) {
        if (results == null || results.size() < 2) {
            return FraudEngineAgreementStatus.INSUFFICIENT_DATA;
        }
        NormalizedFraudEngineResult rules = resultFor(results, "rules.primary");
        NormalizedFraudEngineResult ml = resultFor(results, "ml.python.primary");
        if (rules == null || ml == null) {
            return FraudEngineAgreementStatus.INSUFFICIENT_DATA;
        }
        if (!isComparable(rules)) {
            return FraudEngineAgreementStatus.REQUIRED_ENGINE_NOT_COMPARABLE;
        }
        if (!isComparable(ml)) {
            return ml.status() == FraudEngineStatus.AVAILABLE
                    ? FraudEngineAgreementStatus.INSUFFICIENT_DATA
                    : FraudEngineAgreementStatus.PARTIAL;
        }
        FraudEngineRiskMismatchStatus mismatch = new FraudEngineRiskMismatchCalculator()
                .calculate(List.of(rules, ml))
                .status();
        return mismatch == FraudEngineRiskMismatchStatus.MATERIAL_RISK_MISMATCH
                ? FraudEngineAgreementStatus.DISAGREEMENT
                : FraudEngineAgreementStatus.AGREEMENT;
    }

    private boolean isComparable(NormalizedFraudEngineResult result) {
        return result.status() == FraudEngineStatus.AVAILABLE
                && result.score() != null
                && result.riskLevel() != null;
    }

    private NormalizedFraudEngineResult resultFor(List<NormalizedFraudEngineResult> results, String engineId) {
        return results.stream()
                .filter(result -> engineId.equals(result.engineId()))
                .findFirst()
                .orElse(null);
    }
}
