package com.frauddetection.alert.governance.modelpromotion;

import java.time.Instant;
import java.util.Objects;

public record ModelPromotionMutationResult(
        ModelPromotionAggregate aggregate,
        String decisionId,
        String operationId,
        Instant committedAt,
        boolean idempotentReplay
) {
    public ModelPromotionMutationResult {
        Objects.requireNonNull(aggregate, "aggregate is required");
        Objects.requireNonNull(operationId, "operationId is required");
        Objects.requireNonNull(committedAt, "committedAt is required");
    }

    public ModelPromotionMutationResult asReplay() {
        return new ModelPromotionMutationResult(aggregate, decisionId, operationId, committedAt, true);
    }
}
