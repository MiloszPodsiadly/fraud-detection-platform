package com.frauddetection.scoring.domain;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.source.SourceLineage;

import java.util.Map;
import java.util.Objects;

public record FraudScoringRequest(
        TransactionEnrichedEvent event,
        Map<String, Object> featureSnapshot,
        SourceLineage sourceLineage
) {
    public FraudScoringRequest(TransactionEnrichedEvent event, Map<String, Object> featureSnapshot) {
        this(event, featureSnapshot, SourceLineage.unavailable(SourceLineage.State.UNVERIFIED));
    }
    public FraudScoringRequest {
        sourceLineage = Objects.requireNonNull(sourceLineage, "sourceLineage").forEnriched(event, featureSnapshot);
    }
    public static FraudScoringRequest from(TransactionEnrichedEvent event) {
        return from(event, SourceLineage.unavailable(SourceLineage.State.UNVERIFIED));
    }
    public static FraudScoringRequest from(TransactionEnrichedEvent event, SourceLineage lineage) {
        Map<String, Object> snapshot = event.featureSnapshot() == null ? Map.of() : event.featureSnapshot();
        return new FraudScoringRequest(event, snapshot, lineage);
    }
}
