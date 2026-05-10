package com.frauddetection.alert.persistence;

import com.frauddetection.alert.domain.FraudCaseDecisionType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "fraud_case_decisions")
public class FraudCaseDecisionDocument {

    @Id
    private String id;

    @Indexed
    private String caseId;

    private FraudCaseDecisionType decisionType;
    private String summary;
    private String createdBy;

    @Indexed
    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public FraudCaseDecisionType getDecisionType() { return decisionType; }
    public void setDecisionType(FraudCaseDecisionType decisionType) { this.decisionType = decisionType; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
