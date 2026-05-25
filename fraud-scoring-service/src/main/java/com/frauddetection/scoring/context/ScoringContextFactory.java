package com.frauddetection.scoring.context;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.domain.FraudScoringRequest;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public final class ScoringContextFactory {

    public ScoringContext from(FraudScoringRequest request, ScoringMode mode, Instant receivedAt) {
        Objects.requireNonNull(request, "request is required");
        TransactionEnrichedEvent transaction = Objects.requireNonNull(request.event(), "request event is required");
        Map<String, Object> featureSnapshot = Objects.requireNonNull(
                request.featureSnapshot(),
                "request featureSnapshot is required"
        );

        return new ScoringContext(
                transaction,
                featureSnapshot,
                mode,
                transaction.correlationId(),
                receivedAt
        );
    }
}
