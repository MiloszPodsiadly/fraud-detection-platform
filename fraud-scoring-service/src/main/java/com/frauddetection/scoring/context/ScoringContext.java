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
        featureSnapshot = Map.copyOf(Objects.requireNonNull(featureSnapshot, "featureSnapshot is required"));
        mode = Objects.requireNonNull(mode, "mode is required");
        correlationId = ScoringContextValuePolicy.requireCorrelationId(correlationId);
        receivedAt = Objects.requireNonNull(receivedAt, "receivedAt is required");
    }
}
