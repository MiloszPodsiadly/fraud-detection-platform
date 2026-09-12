package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.features.FeatureSnapshotWireValueNormalizer;

import java.util.Map;
import java.util.Objects;

public final class ValidatedRulesInput {
    private final TransactionEnrichedEvent event;
    private final Map<String, Object> featureSnapshot;

    ValidatedRulesInput(TransactionEnrichedEvent event) {
        this.event = Objects.requireNonNull(event, "event is required");
        this.featureSnapshot = immutableSnapshot(event.featureSnapshot());
    }

    public TransactionEnrichedEvent event() {
        return event;
    }

    public Map<String, Object> featureSnapshot() {
        return featureSnapshot;
    }

    private static Map<String, Object> immutableSnapshot(Map<String, Object> featureSnapshot) {
        if (featureSnapshot == null) {
            return Map.of();
        }
        return FeatureSnapshotWireValueNormalizer.normalize(featureSnapshot);
    }
}
