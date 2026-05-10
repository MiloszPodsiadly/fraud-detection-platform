package com.frauddetection.alert.persistence;

import com.frauddetection.alert.domain.FraudCaseAuditAction;
import com.frauddetection.alert.domain.FraudCaseStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Document(collection = "fraud_case_audit_entries")
public class FraudCaseAuditEntryDocument {

    @Id
    private String id;

    @Indexed
    private String caseId;

    private FraudCaseAuditAction action;
    private String actorId;

    @Indexed
    private Instant occurredAt;

    private FraudCaseStatus previousStatus;
    private FraudCaseStatus newStatus;
    private Map<String, String> details;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public FraudCaseAuditAction getAction() { return action; }
    public void setAction(FraudCaseAuditAction action) { this.action = action; }
    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public FraudCaseStatus getPreviousStatus() { return previousStatus; }
    public void setPreviousStatus(FraudCaseStatus previousStatus) { this.previousStatus = previousStatus; }
    public FraudCaseStatus getNewStatus() { return newStatus; }
    public void setNewStatus(FraudCaseStatus newStatus) { this.newStatus = newStatus; }
    public Map<String, String> getDetails() { return details; }
    public void setDetails(Map<String, String> details) { this.details = details; }
}
