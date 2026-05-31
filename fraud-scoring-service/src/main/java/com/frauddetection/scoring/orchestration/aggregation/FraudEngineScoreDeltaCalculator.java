package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineStatus;

import java.util.List;

public final class FraudEngineScoreDeltaCalculator {

    public FraudEngineScoreDelta calculate(List<NormalizedFraudEngineResult> results) {
        if (results == null || results.size() < 2) {
            return unavailable(FraudEngineScoreDeltaStatus.UNAVAILABLE_NOT_ENOUGH_COMPARABLE_RESULTS);
        }
        NormalizedFraudEngineResult first = results.get(0);
        NormalizedFraudEngineResult second = results.get(1);
        if (first.status() != FraudEngineStatus.AVAILABLE || second.status() != FraudEngineStatus.AVAILABLE) {
            return unavailable(FraudEngineScoreDeltaStatus.UNAVAILABLE_ENGINE_STATUS);
        }
        if (first.score() == null || second.score() == null) {
            return unavailable(FraudEngineScoreDeltaStatus.UNAVAILABLE_MISSING_SCORE);
        }
        return new FraudEngineScoreDelta(
                FraudEngineScoreDeltaStatus.AVAILABLE,
                Math.abs(first.score() - second.score())
        );
    }

    private FraudEngineScoreDelta unavailable(FraudEngineScoreDeltaStatus status) {
        return new FraudEngineScoreDelta(status, null);
    }
}
