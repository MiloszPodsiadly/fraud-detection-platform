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
    @Indexed
    @Field("idempotency_key_hash")
    private String idempotencyKeyHash;
    @Field("intent_hash")
    private String intentHash;
    @Field("intent_resource_id")
    private String intentResourceId;
    @Field("intent_action")
    private String intentAction;
    @Field("intent_actor_id")
    private String intentActorId;
    @Field("intent_decision")
    private String intentDecision;
    @Field("intent_reason_hash")
    private String intentReasonHash;
    @Field("intent_tags_hash")
    private String intentTagsHash;
    @Field("intent_status")
    private String intentStatus;
    @Field("intent_assignee_hash")
    private String intentAssigneeHash;
    @Field("intent_notes_hash")
    private String intentNotesHash;
    @Field("intent_payload_hash")
    private String intentPayloadHash;
    @Field("mutation_model_version")
    private RegulatedMutationModelVersion mutationModelVersion;
    private RegulatedMutationState state;
    @Field("execution_status")
    private RegulatedMutationExecutionStatus executionStatus;
    @Field("lease_owner")
    private String leaseOwner;
    @Field("lease_expires_at")
    private Instant leaseExpiresAt;
    @Field("attempt_count")
    private int attemptCount;
    @Field("last_heartbeat_at")
    private Instant lastHeartbeatAt;
    @Field("public_status")
    private SubmitDecisionOperationStatus publicStatus;
    @Field("response_snapshot")
    private RegulatedMutationResponseSnapshot responseSnapshot;
    @Field("attempted_audit_recorded")
    private boolean attemptedAuditRecorded;
    @Field("attempted_audit_id")
    private String attemptedAuditId;
    @Field("success_audit_recorded")
    private boolean successAuditRecorded;
    @Field("success_audit_id")
    private String successAuditId;
    @Field("failed_audit_id")
    private String failedAuditId;
    @Field("outbox_event_id")
    private String outboxEventId;
    @Field("local_commit_marker")
    private String localCommitMarker;
    @Field("local_committed_at")
    private Instant localCommittedAt;
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
    public String getIdempotencyKeyHash() { return idempotencyKeyHash; }
    public void setIdempotencyKeyHash(String idempotencyKeyHash) { this.idempotencyKeyHash = idempotencyKeyHash; }
    public String getIntentHash() { return intentHash; }
    public void setIntentHash(String intentHash) { this.intentHash = intentHash; }
    public String getIntentResourceId() { return intentResourceId; }
    public void setIntentResourceId(String intentResourceId) { this.intentResourceId = intentResourceId; }
    public String getIntentAction() { return intentAction; }
    public void setIntentAction(String intentAction) { this.intentAction = intentAction; }
    public String getIntentActorId() { return intentActorId; }
    public void setIntentActorId(String intentActorId) { this.intentActorId = intentActorId; }
    public String getIntentDecision() { return intentDecision; }
    public void setIntentDecision(String intentDecision) { this.intentDecision = intentDecision; }
    public String getIntentReasonHash() { return intentReasonHash; }
    public void setIntentReasonHash(String intentReasonHash) { this.intentReasonHash = intentReasonHash; }
    public String getIntentTagsHash() { return intentTagsHash; }
    public void setIntentTagsHash(String intentTagsHash) { this.intentTagsHash = intentTagsHash; }
    public String getIntentStatus() { return intentStatus; }
    public void setIntentStatus(String intentStatus) { this.intentStatus = intentStatus; }
    public String getIntentAssigneeHash() { return intentAssigneeHash; }
    public void setIntentAssigneeHash(String intentAssigneeHash) { this.intentAssigneeHash = intentAssigneeHash; }
    public String getIntentNotesHash() { return intentNotesHash; }
    public void setIntentNotesHash(String intentNotesHash) { this.intentNotesHash = intentNotesHash; }
    public String getIntentPayloadHash() { return intentPayloadHash; }
    public void setIntentPayloadHash(String intentPayloadHash) { this.intentPayloadHash = intentPayloadHash; }
    public RegulatedMutationModelVersion getMutationModelVersion() { return mutationModelVersion; }
    public void setMutationModelVersion(RegulatedMutationModelVersion mutationModelVersion) { this.mutationModelVersion = mutationModelVersion; }
    public RegulatedMutationModelVersion mutationModelVersionOrLegacy() {
        return mutationModelVersion == null ? RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION : mutationModelVersion;
    }
    public RegulatedMutationState getState() { return state; }
    public void setState(RegulatedMutationState state) { this.state = state; }
    public RegulatedMutationExecutionStatus getExecutionStatus() { return executionStatus; }
    public void setExecutionStatus(RegulatedMutationExecutionStatus executionStatus) { this.executionStatus = executionStatus; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(Instant lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }
    public SubmitDecisionOperationStatus getPublicStatus() { return publicStatus; }
    public void setPublicStatus(SubmitDecisionOperationStatus publicStatus) { this.publicStatus = publicStatus; }
    public RegulatedMutationResponseSnapshot getResponseSnapshot() { return responseSnapshot; }
    public void setResponseSnapshot(RegulatedMutationResponseSnapshot responseSnapshot) { this.responseSnapshot = responseSnapshot; }
    public boolean isAttemptedAuditRecorded() { return attemptedAuditRecorded; }
    public void setAttemptedAuditRecorded(boolean attemptedAuditRecorded) { this.attemptedAuditRecorded = attemptedAuditRecorded; }
    public String getAttemptedAuditId() { return attemptedAuditId; }
    public void setAttemptedAuditId(String attemptedAuditId) { this.attemptedAuditId = attemptedAuditId; }
    public boolean isSuccessAuditRecorded() { return successAuditRecorded; }
    public void setSuccessAuditRecorded(boolean successAuditRecorded) { this.successAuditRecorded = successAuditRecorded; }
    public String getSuccessAuditId() { return successAuditId; }
    public void setSuccessAuditId(String successAuditId) { this.successAuditId = successAuditId; }
    public String getFailedAuditId() { return failedAuditId; }
    public void setFailedAuditId(String failedAuditId) { this.failedAuditId = failedAuditId; }
    public String getOutboxEventId() { return outboxEventId; }
    public void setOutboxEventId(String outboxEventId) { this.outboxEventId = outboxEventId; }
    public String getLocalCommitMarker() { return localCommitMarker; }
    public void setLocalCommitMarker(String localCommitMarker) { this.localCommitMarker = localCommitMarker; }
    public Instant getLocalCommittedAt() { return localCommittedAt; }
    public void setLocalCommittedAt(Instant localCommittedAt) { this.localCommittedAt = localCommittedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public String getDegradationReason() { return degradationReason; }
    public void setDegradationReason(String degradationReason) { this.degradationReason = degradationReason; }
}
