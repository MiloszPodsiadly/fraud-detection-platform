package com.frauddetection.alert.persistence;

import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.common.events.enums.RiskLevel;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Document(collection = "fraud_cases")
public class FraudCaseDocument {

    @Id
    private String caseId;

    @Indexed(unique = true)
    private String caseKey;

    @Indexed(unique = true, sparse = true)
    private String caseNumber;

    @Indexed
    private String customerId;

    private String suspicionType;

    @Indexed
    private FraudCaseStatus status;

    @Indexed
    private FraudCasePriority priority;

    @Indexed
    private RiskLevel riskLevel;

    @Indexed
    private List<String> linkedAlertIds;

    @Indexed
    private String assignedInvestigatorId;

    private String createdBy;
    private String reason;
    private BigDecimal thresholdPln;
    private BigDecimal totalAmountPln;
    private String aggregationWindow;
    private Instant firstTransactionAt;
    private Instant lastTransactionAt;
    @Indexed
    private Instant createdAt;
    @Indexed
    private Instant updatedAt;
    private Instant closedAt;
    private String closureReason;
    private String analystId;
    private String decisionReason;
    private List<String> decisionTags;
    private Instant decidedAt;
    private List<String> transactionIds;
    private List<FraudCaseTransactionDocument> transactions;
    @Version
    private Long version;

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getCaseKey() { return caseKey; }
    public void setCaseKey(String caseKey) { this.caseKey = caseKey; }
    public String getCaseNumber() { return caseNumber; }
    public void setCaseNumber(String caseNumber) { this.caseNumber = caseNumber; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getSuspicionType() { return suspicionType; }
    public void setSuspicionType(String suspicionType) { this.suspicionType = suspicionType; }
    public FraudCaseStatus getStatus() { return status; }
    public void setStatus(FraudCaseStatus status) { this.status = status; }
    public FraudCasePriority getPriority() { return priority; }
    public void setPriority(FraudCasePriority priority) { this.priority = priority; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public void setRiskLevel(RiskLevel riskLevel) { this.riskLevel = riskLevel; }
    public List<String> getLinkedAlertIds() { return linkedAlertIds; }
    public void setLinkedAlertIds(List<String> linkedAlertIds) { this.linkedAlertIds = linkedAlertIds; }
    public String getAssignedInvestigatorId() { return assignedInvestigatorId; }
    public void setAssignedInvestigatorId(String assignedInvestigatorId) { this.assignedInvestigatorId = assignedInvestigatorId; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public BigDecimal getThresholdPln() { return thresholdPln; }
    public void setThresholdPln(BigDecimal thresholdPln) { this.thresholdPln = thresholdPln; }
    public BigDecimal getTotalAmountPln() { return totalAmountPln; }
    public void setTotalAmountPln(BigDecimal totalAmountPln) { this.totalAmountPln = totalAmountPln; }
    public String getAggregationWindow() { return aggregationWindow; }
    public void setAggregationWindow(String aggregationWindow) { this.aggregationWindow = aggregationWindow; }
    public Instant getFirstTransactionAt() { return firstTransactionAt; }
    public void setFirstTransactionAt(Instant firstTransactionAt) { this.firstTransactionAt = firstTransactionAt; }
    public Instant getLastTransactionAt() { return lastTransactionAt; }
    public void setLastTransactionAt(Instant lastTransactionAt) { this.lastTransactionAt = lastTransactionAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
    public String getClosureReason() { return closureReason; }
    public void setClosureReason(String closureReason) { this.closureReason = closureReason; }
    public String getAnalystId() { return analystId; }
    public void setAnalystId(String analystId) { this.analystId = analystId; }
    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }
    public List<String> getDecisionTags() { return decisionTags; }
    public void setDecisionTags(List<String> decisionTags) { this.decisionTags = decisionTags; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
    public List<String> getTransactionIds() { return transactionIds; }
    public void setTransactionIds(List<String> transactionIds) { this.transactionIds = transactionIds; }
    public List<FraudCaseTransactionDocument> getTransactions() { return transactions; }
    public void setTransactions(List<FraudCaseTransactionDocument> transactions) { this.transactions = transactions; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
