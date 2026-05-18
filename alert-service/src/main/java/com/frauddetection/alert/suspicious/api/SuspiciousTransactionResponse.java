package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.evidence.EvidenceStatus;
import com.frauddetection.alert.suspicious.DetectionSource;
import com.frauddetection.alert.suspicious.SuspiciousTransactionDocument;
import com.frauddetection.alert.suspicious.SuspiciousTransactionStatus;
import com.frauddetection.common.events.enums.RiskLevel;

import java.time.Instant;
import java.util.List;

public record SuspiciousTransactionResponse(
        String suspiciousTransactionId,
        String transactionId,
        String sourceEventId,
        String correlationId,
        String customerId,
        String accountId,
        Double riskScore,
        RiskLevel riskLevel,
        DetectionSource detectionSource,
        List<String> reasonCodes,
        EvidenceStatus evidenceStatus,
        Integer evidenceSnapshotItemCount,
        String evidenceProjectionState,
        String linkedAlertId,
        SuspiciousTransactionStatus status,
        Instant detectedAt,
        Instant createdAt,
        Instant updatedAt,
        String scoreDecisionId,
        String scoringStrategy,
        String modelName,
        String modelVersion
) {

    public SuspiciousTransactionResponse {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }

    static SuspiciousTransactionResponse from(SuspiciousTransactionDocument document) {
        return new SuspiciousTransactionResponse(
                document.getSuspiciousTransactionId(),
                document.getTransactionId(),
                document.getSourceEventId(),
                document.getCorrelationId(),
                document.getCustomerId(),
                document.getAccountId(),
                document.getRiskScore(),
                document.getRiskLevel(),
                document.getDetectionSource(),
                document.getReasonCodes(),
                document.getEvidenceStatus(),
                document.getEvidenceSnapshotItemCount(),
                document.getEvidenceProjectionState(),
                document.getLinkedAlertId(),
                document.getStatus(),
                document.getDetectedAt(),
                document.getCreatedAt(),
                document.getUpdatedAt(),
                document.getScoreDecisionId(),
                document.getScoringStrategy(),
                document.getModelName(),
                document.getModelVersion()
        );
    }
}
