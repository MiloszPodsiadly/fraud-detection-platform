package com.frauddetection.scoring.domain;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;

import java.util.Map;

public record FraudScoringRequest(
        TransactionEnrichedEvent event,
        Map<String, Object> featureSnapshot
) {

    public static FraudScoringRequest from(TransactionEnrichedEvent event) {
        Map<String, Object> snapshot = event.featureSnapshot() == null ? Map.of() : event.featureSnapshot();
        return new FraudScoringRequest(event, snapshot);
    }
}
