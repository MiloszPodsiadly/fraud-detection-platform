package com.frauddetection.alert.suspicious;

import com.frauddetection.alert.evidence.EvidenceStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.IndexDirection;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "suspicious_transactions")
@CompoundIndex(
        name = "suspicious_transaction_source_event_unique_idx",
        def = "{'transactionId': 1, 'sourceEventId': 1}",
        unique = true
)
public class SuspiciousTransactionDocument {

    @Id
    private String suspiciousTransactionId;

    @Indexed
    private String transactionId;

    @Indexed
    private String sourceEventId;

    @Indexed
    private String correlationId;

    @Indexed
    private String customerId;

    private String accountId;

    private Double riskScore;
    private RiskLevel riskLevel;
    private DetectionSource detectionSource;

    private List<String> reasonCodes = List.of();

    private EvidenceStatus evidenceStatus;
    private Integer evidenceSnapshotItemCount;
    private String evidenceProjectionState;

    @Indexed
    private String linkedAlertId;

    @Indexed
    private SuspiciousTransactionStatus status;

    @Indexed(name = "suspicious_transaction_detected_at_idx", direction = IndexDirection.DESCENDING)
    private Instant detectedAt;
    private Instant createdAt;
    private Instant updatedAt;

    private String scoreDecisionId;
    private String scoringStrategy;
    private String modelName;
    private String modelVersion;

    public String getSuspiciousTransactionId() {
        return suspiciousTransactionId;
    }

    public void setSuspiciousTransactionId(String suspiciousTransactionId) {
        this.suspiciousTransactionId = suspiciousTransactionId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getSourceEventId() {
        return sourceEventId;
    }

    public void setSourceEventId(String sourceEventId) {
        this.sourceEventId = sourceEventId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public Double getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(Double riskScore) {
        this.riskScore = riskScore;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(RiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public DetectionSource getDetectionSource() {
        return detectionSource;
    }

    public void setDetectionSource(DetectionSource detectionSource) {
        this.detectionSource = detectionSource;
    }

    public List<String> getReasonCodes() {
        return reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }

    public void setReasonCodes(List<String> reasonCodes) {
        this.reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }

    public EvidenceStatus getEvidenceStatus() {
        return evidenceStatus;
    }

    public void setEvidenceStatus(EvidenceStatus evidenceStatus) {
        this.evidenceStatus = evidenceStatus;
    }

    public Integer getEvidenceSnapshotItemCount() {
        return evidenceSnapshotItemCount;
    }

    public void setEvidenceSnapshotItemCount(Integer evidenceSnapshotItemCount) {
        this.evidenceSnapshotItemCount = evidenceSnapshotItemCount;
    }

    public String getEvidenceProjectionState() {
        return evidenceProjectionState;
    }

    public void setEvidenceProjectionState(String evidenceProjectionState) {
        this.evidenceProjectionState = evidenceProjectionState;
    }

    public String getLinkedAlertId() {
        return linkedAlertId;
    }

    public void setLinkedAlertId(String linkedAlertId) {
        this.linkedAlertId = linkedAlertId;
    }

    public SuspiciousTransactionStatus getStatus() {
        return status;
    }

    public void setStatus(SuspiciousTransactionStatus status) {
        this.status = status;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public void setDetectedAt(Instant detectedAt) {
        this.detectedAt = detectedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getScoreDecisionId() {
        return scoreDecisionId;
    }

    public void setScoreDecisionId(String scoreDecisionId) {
        this.scoreDecisionId = scoreDecisionId;
    }

    public String getScoringStrategy() {
        return scoringStrategy;
    }

    public void setScoringStrategy(String scoringStrategy) {
        this.scoringStrategy = scoringStrategy;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }
}
