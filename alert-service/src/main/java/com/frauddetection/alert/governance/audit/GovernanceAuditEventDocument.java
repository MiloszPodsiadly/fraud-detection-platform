package com.frauddetection.alert.governance.audit;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.List;

@Document(collection = "ml_governance_audit_events")
@CompoundIndex(name = "advisory_event_created_at_idx", def = "{'advisory_event_id': 1, 'created_at': -1}")
@CompoundIndex(name = "actor_created_at_idx", def = "{'actor_id': 1, 'created_at': -1}")
@CompoundIndex(name = "model_version_created_at_idx", def = "{'model_name': 1, 'model_version': 1, 'created_at': -1}")
public class GovernanceAuditEventDocument {

    @Id
    private String auditId;

    @Field("advisory_event_id")
    private String advisoryEventId;

    private GovernanceAuditDecision decision;

    private String note;

    @Field("actor_id")
    private String actorId;

    @Field("actor_display_name")
    private String actorDisplayName;

    @Field("actor_roles")
    private List<String> actorRoles;

    @Field("created_at")
    private Instant createdAt;

    @Field("model_name")
    private String modelName;

    @Field("model_version")
    private String modelVersion;

    @Field("advisory_severity")
    private String advisorySeverity;

    @Field("advisory_confidence")
    private String advisoryConfidence;

    @Field("advisory_confidence_context")
    private String advisoryConfidenceContext;

    public String getAuditId() {
        return auditId;
    }

    public void setAuditId(String auditId) {
        this.auditId = auditId;
    }

    public String getAdvisoryEventId() {
        return advisoryEventId;
    }

    public void setAdvisoryEventId(String advisoryEventId) {
        this.advisoryEventId = advisoryEventId;
    }

    public GovernanceAuditDecision getDecision() {
        return decision;
    }

    public void setDecision(GovernanceAuditDecision decision) {
        this.decision = decision;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public String getActorDisplayName() {
        return actorDisplayName;
    }

    public void setActorDisplayName(String actorDisplayName) {
        this.actorDisplayName = actorDisplayName;
    }

    public List<String> getActorRoles() {
        return actorRoles;
    }

    public void setActorRoles(List<String> actorRoles) {
        this.actorRoles = actorRoles;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
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

    public String getAdvisorySeverity() {
        return advisorySeverity;
    }

    public void setAdvisorySeverity(String advisorySeverity) {
        this.advisorySeverity = advisorySeverity;
    }

    public String getAdvisoryConfidence() {
        return advisoryConfidence;
    }

    public void setAdvisoryConfidence(String advisoryConfidence) {
        this.advisoryConfidence = advisoryConfidence;
    }

    public String getAdvisoryConfidenceContext() {
        return advisoryConfidenceContext;
    }

    public void setAdvisoryConfidenceContext(String advisoryConfidenceContext) {
        this.advisoryConfidenceContext = advisoryConfidenceContext;
    }
}
