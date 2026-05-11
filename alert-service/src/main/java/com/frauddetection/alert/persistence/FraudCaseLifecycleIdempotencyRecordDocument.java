package com.frauddetection.alert.persistence;

import com.frauddetection.alert.fraudcase.FraudCaseLifecycleIdempotencyStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Document(collection = "fraud_case_lifecycle_idempotency_records")
public class FraudCaseLifecycleIdempotencyRecordDocument {

    @Id
    private String id;

    @Indexed(name = "fraud_case_lifecycle_idempotency_key_hash_idx", unique = true)
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
