package com.frauddetection.alert.evidence;

import com.frauddetection.common.events.evidence.ScoringEvidenceAttributes;

import java.util.Map;

public final class AlertEvidenceSnapshotAttributes {

    private AlertEvidenceSnapshotAttributes() {
    }

    public static Map<String, Object> safeCopy(Map<String, Object> attributes) {
        return ScoringEvidenceAttributes.safeCopy(attributes);
    }
}
