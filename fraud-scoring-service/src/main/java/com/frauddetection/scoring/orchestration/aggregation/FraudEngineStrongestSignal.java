package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineEvidenceType;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;

import java.util.Objects;
import java.util.Set;

public record FraudEngineStrongestSignal(
        String engineId,
        FraudEngineType engineType,
        FraudEngineStatus status,
        RiskLevel riskLevel,
        Double score,
        String reasonCode,
        FraudEngineEvidenceType evidenceType,
        FraudEngineSignalCategory signalCategory
) {
    private static final Set<String> ALLOWED_ENGINE_IDS = Set.of("rules.primary", "ml.python.primary");

    public FraudEngineStrongestSignal {
        if (!ALLOWED_ENGINE_IDS.contains(engineId)) {
            throw new IllegalArgumentException("AGGREGATION_SIGNAL_UNKNOWN_ENGINE_ID");
        }
        Objects.requireNonNull(engineType, "engineType is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(reasonCode, "reasonCode is required");
        Objects.requireNonNull(signalCategory, "signalCategory is required");
        if (score != null && (!Double.isFinite(score) || score < 0.0d || score > 1.0d)) {
            throw new IllegalArgumentException("AGGREGATION_SIGNAL_SCORE_OUT_OF_RANGE");
        }
        if (!FraudEngineAggregationSafety.isSafe(reasonCode)) {
            throw new IllegalArgumentException("AGGREGATION_SIGNAL_UNSAFE_REASON_CODE");
        }
        if (!new FraudEngineReasonCodeNormalizer().isAllowed(reasonCode)) {
            throw new IllegalArgumentException("AGGREGATION_SIGNAL_UNNORMALIZED_REASON_CODE");
        }
    }
}
