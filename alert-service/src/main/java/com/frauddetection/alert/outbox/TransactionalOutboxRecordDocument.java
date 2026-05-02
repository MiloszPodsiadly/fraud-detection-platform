package com.frauddetection.alert.outbox;

import com.frauddetection.common.events.contract.FraudDecisionEvent;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Document(collection = "transactional_outbox_records")
@CompoundIndex(name = "status_created_idx", def = "{'status': 1, 'created_at': 1}")
public class TransactionalOutboxRecordDocument {

    @Id
    @Field("event_id")
    private String eventId;
    @Indexed(unique = true)
    @Field("dedupe_key")
    private String dedupeKey;
    @Indexed
    @Field("mutation_command_id")
    private String mutationCommandId;
    @Field("resource_type")
    private String resourceType;
    @Field("resource_id")
    private String resourceId;
    @Field("event_type")
    private String eventType;
    @Field("payload_hash")
    private String payloadHash;
    private FraudDecisionEvent payload;
    private TransactionalOutboxStatus status;
    private int attempts;
    @Field("lease_owner")
    private String leaseOwner;
    @Field("lease_expires_at")
    private Instant leaseExpiresAt;
    @Field("last_error")
    private String lastError;
    @Field("published_at")
    private Instant publishedAt;
    @Field("confirmation_unknown_at")
    private Instant confirmationUnknownAt;
    @Field("publish_attempted_at")
    private Instant publishAttemptedAt;
    @Field("projection_mismatch")
    private boolean projectionMismatch;
    @Field("projection_mismatch_reason")
    private String projectionMismatchReason;
    @Field("resolution_pending")
    private boolean resolutionPending;
    @Field("resolution_control_mode")
    private String resolutionControlMode;
    @Field("resolution_requested_by")
    private String resolutionRequestedBy;
    @Field("resolution_requested_at")
    private Instant resolutionRequestedAt;
    @Field("resolution_reason")
    private String resolutionReason;
    @Field("resolution_evidence_type")
    private String resolutionEvidenceType;
    @Field("resolution_evidence_reference")
    private String resolutionEvidenceReference;
    @Field("resolution_evidence_verified_at")
    private Instant resolutionEvidenceVerifiedAt;
    @Field("resolution_evidence_verified_by")
    private String resolutionEvidenceVerifiedBy;
    @Field("resolution_approved_by")
    private String resolutionApprovedBy;
    @Field("resolution_approved_at")
    private Instant resolutionApprovedAt;
    @Field("created_at")
    private Instant createdAt;
    @Field("updated_at")
    private Instant updatedAt;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getDedupeKey() { return dedupeKey; }
    public void setDedupeKey(String dedupeKey) { this.dedupeKey = dedupeKey; }
    public String getMutationCommandId() { return mutationCommandId; }
    public void setMutationCommandId(String mutationCommandId) { this.mutationCommandId = mutationCommandId; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public FraudDecisionEvent getPayload() { return payload; }
    public void setPayload(FraudDecisionEvent payload) { this.payload = payload; }
    public TransactionalOutboxStatus getStatus() { return status; }
    public void setStatus(TransactionalOutboxStatus status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public Instant getConfirmationUnknownAt() { return confirmationUnknownAt; }
    public void setConfirmationUnknownAt(Instant confirmationUnknownAt) { this.confirmationUnknownAt = confirmationUnknownAt; }
    public Instant getPublishAttemptedAt() { return publishAttemptedAt; }
    public void setPublishAttemptedAt(Instant publishAttemptedAt) { this.publishAttemptedAt = publishAttemptedAt; }
    public boolean isProjectionMismatch() { return projectionMismatch; }
    public void setProjectionMismatch(boolean projectionMismatch) { this.projectionMismatch = projectionMismatch; }
    public String getProjectionMismatchReason() { return projectionMismatchReason; }
    public void setProjectionMismatchReason(String projectionMismatchReason) { this.projectionMismatchReason = projectionMismatchReason; }
    public boolean isResolutionPending() { return resolutionPending; }
    public void setResolutionPending(boolean resolutionPending) { this.resolutionPending = resolutionPending; }
    public String getResolutionControlMode() { return resolutionControlMode; }
    public void setResolutionControlMode(String resolutionControlMode) { this.resolutionControlMode = resolutionControlMode; }
    public String getResolutionRequestedBy() { return resolutionRequestedBy; }
    public void setResolutionRequestedBy(String resolutionRequestedBy) { this.resolutionRequestedBy = resolutionRequestedBy; }
    public Instant getResolutionRequestedAt() { return resolutionRequestedAt; }
    public void setResolutionRequestedAt(Instant resolutionRequestedAt) { this.resolutionRequestedAt = resolutionRequestedAt; }
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
    public String getResolutionApprovedBy() { return resolutionApprovedBy; }
    public void setResolutionApprovedBy(String resolutionApprovedBy) { this.resolutionApprovedBy = resolutionApprovedBy; }
    public Instant getResolutionApprovedAt() { return resolutionApprovedAt; }
    public void setResolutionApprovedAt(Instant resolutionApprovedAt) { this.resolutionApprovedAt = resolutionApprovedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
