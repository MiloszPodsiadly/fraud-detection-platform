package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Document(collection = "regulated_mutation_commands")
@CompoundIndex(name = "resource_action_created_idx", def = "{'resource_id': 1, 'action': 1, 'created_at': -1}")
public class RegulatedMutationCommandDocument {

    @Id
    private String id;

    @Indexed(unique = true)
    @Field("idempotency_key")
    private String idempotencyKey;

    @Field("actor_id")
    private String actorId;
    @Field("resource_id")
    private String resourceId;
    @Field("resource_type")
    private String resourceType;
    private String action;
    @Field("correlation_id")
    private String correlationId;
    @Field("request_hash")
    private String requestHash;
    private RegulatedMutationState state;
    @Field("public_status")
    private SubmitDecisionOperationStatus publicStatus;
    @Field("response_snapshot")
    private RegulatedMutationResponseSnapshot responseSnapshot;
    @Field("attempted_audit_recorded")
    private boolean attemptedAuditRecorded;
    @Field("success_audit_recorded")
    private boolean successAuditRecorded;
    @Field("outbox_event_id")
    private String outboxEventId;
    @Field("created_at")
    private Instant createdAt;
    @Field("updated_at")
    private Instant updatedAt;
    @Field("last_error")
    private String lastError;
    @Field("degradation_reason")
    private String degradationReason;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    public RegulatedMutationState getState() { return state; }
    public void setState(RegulatedMutationState state) { this.state = state; }
    public SubmitDecisionOperationStatus getPublicStatus() { return publicStatus; }
    public void setPublicStatus(SubmitDecisionOperationStatus publicStatus) { this.publicStatus = publicStatus; }
    public RegulatedMutationResponseSnapshot getResponseSnapshot() { return responseSnapshot; }
    public void setResponseSnapshot(RegulatedMutationResponseSnapshot responseSnapshot) { this.responseSnapshot = responseSnapshot; }
    public boolean isAttemptedAuditRecorded() { return attemptedAuditRecorded; }
    public void setAttemptedAuditRecorded(boolean attemptedAuditRecorded) { this.attemptedAuditRecorded = attemptedAuditRecorded; }
    public boolean isSuccessAuditRecorded() { return successAuditRecorded; }
    public void setSuccessAuditRecorded(boolean successAuditRecorded) { this.successAuditRecorded = successAuditRecorded; }
    public String getOutboxEventId() { return outboxEventId; }
    public void setOutboxEventId(String outboxEventId) { this.outboxEventId = outboxEventId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public String getDegradationReason() { return degradationReason; }
    public void setDegradationReason(String degradationReason) { this.degradationReason = degradationReason; }
}
