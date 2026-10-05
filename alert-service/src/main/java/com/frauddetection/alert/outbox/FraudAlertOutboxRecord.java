package com.frauddetection.alert.outbox;

import com.frauddetection.common.events.contract.FraudAlertEvent;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "fraud_alert_outbox_records")
@CompoundIndex(name = "alert_outbox_status_created_idx", def = "{'status': 1, 'createdAt': 1}")
public class FraudAlertOutboxRecord {

    @Id
    private String eventId;

    @Indexed(unique = true)
    private String alertId;

    private String transactionId;
    private FraudAlertEvent payload;
    private FraudAlertOutboxStatus status;
    private int attempts;
    private String leaseOwner;
    private String leaseToken;
    private Instant leaseExpiresAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant publishAttemptedAt;
    private Instant publishedAt;
    private Instant confirmationUnknownAt;
    private Instant terminalAt;
    private String lastError;
    private long revision;
    private String resolution;
    private String resolutionIdempotencyHash;
    private String resolutionRequestHash;
    private String resolutionReason;
    private String resolutionEvidenceType;
    private String resolutionEvidenceReference;
    private Instant resolutionEvidenceVerifiedAt;
    private String resolutionEvidenceVerifiedBy;
    private String resolvedBy;
    private Instant resolvedAt;
    private Integer resolutionPreviousAttempts;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getAlertId() { return alertId; }
    public void setAlertId(String alertId) { this.alertId = alertId; }
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public FraudAlertEvent getPayload() { return payload; }
    public void setPayload(FraudAlertEvent payload) { this.payload = payload; }
    public FraudAlertOutboxStatus getStatus() { return status; }
    public void setStatus(FraudAlertOutboxStatus status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public String getLeaseToken() { return leaseToken; }
    public void setLeaseToken(String leaseToken) { this.leaseToken = leaseToken; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getPublishAttemptedAt() { return publishAttemptedAt; }
    public void setPublishAttemptedAt(Instant publishAttemptedAt) { this.publishAttemptedAt = publishAttemptedAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public Instant getConfirmationUnknownAt() { return confirmationUnknownAt; }
    public void setConfirmationUnknownAt(Instant confirmationUnknownAt) { this.confirmationUnknownAt = confirmationUnknownAt; }
    public Instant getTerminalAt() { return terminalAt; }
    public void setTerminalAt(Instant terminalAt) { this.terminalAt = terminalAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }
    public String getResolutionIdempotencyHash() { return resolutionIdempotencyHash; }
    public void setResolutionIdempotencyHash(String resolutionIdempotencyHash) { this.resolutionIdempotencyHash = resolutionIdempotencyHash; }
    public String getResolutionRequestHash() { return resolutionRequestHash; }
    public void setResolutionRequestHash(String resolutionRequestHash) { this.resolutionRequestHash = resolutionRequestHash; }
    public String getResolutionReason() { return resolutionReason; }
    public void setResolutionReason(String resolutionReason) { this.resolutionReason = resolutionReason; }
    public String getResolutionEvidenceType() { return resolutionEvidenceType; }
    public void setResolutionEvidenceType(String resolutionEvidenceType) { this.resolutionEvidenceType = resolutionEvidenceType; }
    public String getResolutionEvidenceReference() { return resolutionEvidenceReference; }
    public void setResolutionEvidenceReference(String resolutionEvidenceReference) { this.resolutionEvidenceReference = resolutionEvidenceReference; }
    public Instant getResolutionEvidenceVerifiedAt() { return resolutionEvidenceVerifiedAt; }
    public void setResolutionEvidenceVerifiedAt(Instant resolutionEvidenceVerifiedAt) { this.resolutionEvidenceVerifiedAt = resolutionEvidenceVerifiedAt; }
    public String getResolutionEvidenceVerifiedBy() { return resolutionEvidenceVerifiedBy; }
    public void setResolutionEvidenceVerifiedBy(String resolutionEvidenceVerifiedBy) { this.resolutionEvidenceVerifiedBy = resolutionEvidenceVerifiedBy; }
    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public Integer getResolutionPreviousAttempts() { return resolutionPreviousAttempts; }
    public void setResolutionPreviousAttempts(Integer resolutionPreviousAttempts) { this.resolutionPreviousAttempts = resolutionPreviousAttempts; }
}
