package com.frauddetection.alert.api;

import com.frauddetection.alert.evidence.EvidenceStatus;

public record EvidenceStatusCountResponse(
        EvidenceStatus status,
        long count
) {
}
