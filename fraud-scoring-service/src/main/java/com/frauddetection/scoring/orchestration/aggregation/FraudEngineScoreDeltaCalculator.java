package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineStatus;

import java.util.List;

public final class FraudEngineScoreDeltaCalculator {

    public FraudEngineScoreDelta calculate(List<NormalizedFraudEngineResult> results) {
        if (results == null || results.size() < 2) {
            return unavailable(FraudEngineScoreDeltaStatus.UNAVAILABLE_NOT_ENOUGH_COMPARABLE_RESULTS);
        }
        NormalizedFraudEngineResult rules = resultFor(results, "rules.primary");
        NormalizedFraudEngineResult ml = resultFor(results, "ml.python.primary");
        if (rules == null || ml == null) {
            return unavailable(FraudEngineScoreDeltaStatus.UNAVAILABLE_NOT_ENOUGH_COMPARABLE_RESULTS);
        }
        if (rules.status() != FraudEngineStatus.AVAILABLE || ml.status() != FraudEngineStatus.AVAILABLE) {
            return unavailable(FraudEngineScoreDeltaStatus.UNAVAILABLE_ENGINE_STATUS);
        }
        if (rules.score() == null || ml.score() == null) {
            return unavailable(FraudEngineScoreDeltaStatus.UNAVAILABLE_MISSING_SCORE);
        }
        return new FraudEngineScoreDelta(
                FraudEngineScoreDeltaStatus.AVAILABLE,
                Math.abs(rules.score() - ml.score())
        );
    }

    private FraudEngineScoreDelta unavailable(FraudEngineScoreDeltaStatus status) {
        return new FraudEngineScoreDelta(status, null);
    }

    private NormalizedFraudEngineResult resultFor(List<NormalizedFraudEngineResult> results, String engineId) {
        return results.stream()
                .filter(result -> engineId.equals(result.engineId()))
                .findFirst()
                .orElse(null);
    }
}
