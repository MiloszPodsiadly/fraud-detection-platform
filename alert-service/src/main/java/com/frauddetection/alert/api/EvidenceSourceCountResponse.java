package com.frauddetection.alert.api;

import com.frauddetection.alert.evidence.EvidenceSource;

public record EvidenceSourceCountResponse(
        EvidenceSource source,
        long count
) {
}
