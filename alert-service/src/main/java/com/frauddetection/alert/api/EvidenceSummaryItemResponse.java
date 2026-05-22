package com.frauddetection.alert.api;

import com.frauddetection.alert.evidence.EvidenceSeverity;
import com.frauddetection.alert.evidence.EvidenceSource;
import com.frauddetection.alert.evidence.EvidenceStatus;
import com.frauddetection.alert.evidence.EvidenceType;

public record EvidenceSummaryItemResponse(
        String reasonCode,
        EvidenceType evidenceType,
        EvidenceSeverity severity,
        EvidenceSource source,
        EvidenceStatus status,
        String title,
        String description
) {
}
