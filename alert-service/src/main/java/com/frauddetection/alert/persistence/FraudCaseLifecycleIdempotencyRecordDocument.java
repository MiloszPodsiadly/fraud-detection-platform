package com.frauddetection.alert.persistence;

import com.frauddetection.alert.fraudcase.FraudCaseLifecycleIdempotencyStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Document(collection = "fraud_case_lifecycle_idempotency_records")
@CompoundIndex(
        name = "fraud_case_lifecycle_idempotency_scope_idx",
        def = "{'idempotency_key_hash': 1, 'action': 1, 'actor_id': 1, 'case_id_scope': 1}",
        unique = true
)
public class FraudCaseLifecycleIdempotencyRecordDocument {

    @Id
    private String id;

    @Field("idempotency_key_hash")
    private String idempotencyKeyHash;

    private String action;

    @Field("actor_id")
    private String actorId;

    @Field("case_id")
    private String caseId;

    @Field("case_id_scope")
    private String caseIdScope;

    @Field("request_hash")
    private String requestHash;

    @Field("response_payload_snapshot")
    private String responsePayloadSnapshot;

    @Field("response_status")
    private String responseStatus;

    private FraudCaseLifecycleIdempotencyStatus status;

    @Field("created_at")
    private Instant createdAt;

    @Field("completed_at")
    private Instant completedAt;

    @Indexed(expireAfterSeconds = 0)
    @Field("expires_at")
    private Instant expiresAt;

    @Version
    private Long version;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getIdempotencyKeyHash() { return idempotencyKeyHash; }
    public void setIdempotencyKeyHash(String idempotencyKeyHash) { this.idempotencyKeyHash = idempotencyKeyHash; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }
    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getCaseIdScope() { return caseIdScope; }
    public void setCaseIdScope(String caseIdScope) { this.caseIdScope = caseIdScope; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    public String getResponsePayloadSnapshot() { return responsePayloadSnapshot; }
    public void setResponsePayloadSnapshot(String responsePayloadSnapshot) { this.responsePayloadSnapshot = responsePayloadSnapshot; }
    public String getResponseStatus() { return responseStatus; }
    public void setResponseStatus(String responseStatus) { this.responseStatus = responseStatus; }
    public FraudCaseLifecycleIdempotencyStatus getStatus() { return status; }
    public void setStatus(FraudCaseLifecycleIdempotencyStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
