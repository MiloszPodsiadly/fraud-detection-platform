package com.frauddetection.alert.api;

import com.frauddetection.alert.evidence.EvidenceStatus;

import java.time.Instant;
import java.util.List;

public record FraudCaseEvidenceSummaryResponse(
        String caseId,
        EvidenceStatus aggregateEvidenceStatus,
        List<String> topReasonCodes,
        List<EvidenceSummaryItemResponse> highestSeverityEvidence,
        List<EvidenceSourceCountResponse> evidenceBySource,
        List<EvidenceStatusCountResponse> evidenceByStatus,
        int linkedAlertCount,
        int evidenceItemCount,
        boolean partial,
        boolean legacy,
        boolean truncated,
        String truncationReason,
        Instant generatedAt
) {
}
