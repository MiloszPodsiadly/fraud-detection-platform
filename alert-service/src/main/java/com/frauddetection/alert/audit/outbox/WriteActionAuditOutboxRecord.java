package com.frauddetection.alert.audit.outbox;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Document(collection = "write_action_audit_outbox")
@CompoundIndex(name = "write_action_audit_publishable_v2_idx", def = "{'status': 1, 'next_attempt_at': 1, 'claim_expires_at': 1, 'created_at': 1}")
public class WriteActionAuditOutboxRecord {

    @Id
    @Field("outbox_id")
    private String outboxId;

    @Indexed(unique = true)
    @Field("idempotency_key")
    private String idempotencyKey;

    @Field("contract_version")
    private String contractVersion;

    private AuditAction action;

    @Field("resource_type")
    private AuditResourceType resourceType;

    @Field("resource_id")
    private String resourceId;

    @Field("correlation_id")
    private String correlationId;

    private String actor;
    private AuditOutcome outcome;

    @Field("metadata_summary")
    private AuditEventMetadataSummary metadataSummary;

    private WriteActionAuditOutboxStatus status;

    @Field("attempt_count")
    private int attemptCount;

    @Field("max_attempts")
    private int maxAttempts;

    @Field("next_attempt_at")
    private Instant nextAttemptAt;

    @Field("created_at")
    private Instant createdAt;

    @Field("last_attempt_at")
    private Instant lastAttemptAt;

    @Field("claimed_at")
    private Instant claimedAt;

    @Field("claim_owner")
    private String claimOwner;

    @Field("claim_expires_at")
    private Instant claimExpiresAt;

    @Field("published_at")
    private Instant publishedAt;

    @Field("last_error_code")
    private String lastErrorCode;

    @Field("last_error_message")
    private String lastErrorMessage;

    public String getOutboxId() { return outboxId; }
    public void setOutboxId(String outboxId) { this.outboxId = outboxId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getContractVersion() { return contractVersion; }
    public void setContractVersion(String contractVersion) { this.contractVersion = contractVersion; }
    public AuditAction getAction() { return action; }
    public void setAction(AuditAction action) { this.action = action; }
    public AuditResourceType getResourceType() { return resourceType; }
    public void setResourceType(AuditResourceType resourceType) { this.resourceType = resourceType; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public AuditOutcome getOutcome() { return outcome; }
    public void setOutcome(AuditOutcome outcome) { this.outcome = outcome; }
    public AuditEventMetadataSummary getMetadataSummary() { return metadataSummary; }
    public void setMetadataSummary(AuditEventMetadataSummary metadataSummary) { this.metadataSummary = metadataSummary; }
    public WriteActionAuditOutboxStatus getStatus() { return status; }
    public void setStatus(WriteActionAuditOutboxStatus status) { this.status = status; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(Instant lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }
    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }
    public String getClaimOwner() { return claimOwner; }
    public void setClaimOwner(String claimOwner) { this.claimOwner = claimOwner; }
    public Instant getClaimExpiresAt() { return claimExpiresAt; }
    public void setClaimExpiresAt(Instant claimExpiresAt) { this.claimExpiresAt = claimExpiresAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }
    public String getLastErrorMessage() { return lastErrorMessage; }
    public void setLastErrorMessage(String lastErrorMessage) { this.lastErrorMessage = lastErrorMessage; }
}
