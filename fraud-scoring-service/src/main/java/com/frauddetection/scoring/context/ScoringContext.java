package com.frauddetection.scoring.context;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.scoring.config.ScoringMode;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record ScoringContext(
        TransactionEnrichedEvent transaction,
        Map<String, Object> featureSnapshot,
        ScoringMode mode,
        String correlationId,
        Instant receivedAt
) {

    public ScoringContext {
        transaction = Objects.requireNonNull(transaction, "transaction is required");
        featureSnapshot = copyFeatureSnapshot(featureSnapshot);
        mode = Objects.requireNonNull(mode, "mode is required");
        correlationId = ScoringContextValuePolicy.requireCorrelationId(correlationId);
        receivedAt = Objects.requireNonNull(receivedAt, "receivedAt is required");
    }

    private static Map<String, Object> copyFeatureSnapshot(Map<String, Object> source) {
        Objects.requireNonNull(source, "featureSnapshot is required");
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException("featureSnapshot must not contain null keys");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("featureSnapshot must not contain null values");
            }
        }
        return Map.copyOf(source);
    }
}
