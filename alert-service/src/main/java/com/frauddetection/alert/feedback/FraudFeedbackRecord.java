package com.frauddetection.alert.feedback;

import com.frauddetection.alert.api.EngineIntelligenceResponseStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.recommendation.AnalystRecommendation;
import com.frauddetection.common.events.recommendation.AnalystRecommendationStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "fraud_feedback_records")
public class FraudFeedbackRecord {

    @Id
    private String feedbackId;

    @Indexed(unique = true)
    private String transactionId;

    private String customerId;
    private String correlationId;
    private AnalystDecision analystDecision;
    private FraudFeedbackLabel feedbackLabel;
    private FeedbackLabelSource labelSource;
    private FraudFeedbackStatus feedbackStatus;
    private Instant createdAt;
    private String createdBy;
    private List<String> decisionReasonCodes;
    private String notes;
    private Double fraudScore;
    private RiskLevel riskLevel;
    private Boolean alertRecommended;
    private Instant scoredAt;
    private Instant transactionTimestamp;
    private EngineIntelligenceResponseStatus engineIntelligenceStatus;
    private EngineIntelligenceAgreementStatus agreementStatus;
    private EngineIntelligenceRiskMismatchStatus riskMismatchStatus;
    private EngineIntelligenceScoreDeltaBucket scoreDeltaBucket;
    private AnalystRecommendationStatus analystRecommendationStatus;
    private AnalystRecommendation analystRecommendation;
    private String analystRecommendationVersion;
    private Instant analystRecommendationGeneratedAt;
    private List<String> analystRecommendationReasonCodes;

    public String getFeedbackId() { return feedbackId; }
    public void setFeedbackId(String feedbackId) { this.feedbackId = feedbackId; }
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public AnalystDecision getAnalystDecision() { return analystDecision; }
    public void setAnalystDecision(AnalystDecision analystDecision) { this.analystDecision = analystDecision; }
    public FraudFeedbackLabel getFeedbackLabel() { return feedbackLabel; }
    public void setFeedbackLabel(FraudFeedbackLabel feedbackLabel) { this.feedbackLabel = feedbackLabel; }
    public FeedbackLabelSource getLabelSource() { return labelSource; }
    public void setLabelSource(FeedbackLabelSource labelSource) { this.labelSource = labelSource; }
    public FraudFeedbackStatus getFeedbackStatus() { return feedbackStatus; }
    public void setFeedbackStatus(FraudFeedbackStatus feedbackStatus) { this.feedbackStatus = feedbackStatus; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public List<String> getDecisionReasonCodes() { return decisionReasonCodes; }
    public void setDecisionReasonCodes(List<String> decisionReasonCodes) { this.decisionReasonCodes = decisionReasonCodes; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Double getFraudScore() { return fraudScore; }
    public void setFraudScore(Double fraudScore) { this.fraudScore = fraudScore; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public void setRiskLevel(RiskLevel riskLevel) { this.riskLevel = riskLevel; }
    public Boolean getAlertRecommended() { return alertRecommended; }
    public void setAlertRecommended(Boolean alertRecommended) { this.alertRecommended = alertRecommended; }
    public Instant getScoredAt() { return scoredAt; }
    public void setScoredAt(Instant scoredAt) { this.scoredAt = scoredAt; }
    public Instant getTransactionTimestamp() { return transactionTimestamp; }
    public void setTransactionTimestamp(Instant transactionTimestamp) { this.transactionTimestamp = transactionTimestamp; }
    public EngineIntelligenceResponseStatus getEngineIntelligenceStatus() { return engineIntelligenceStatus; }
    public void setEngineIntelligenceStatus(EngineIntelligenceResponseStatus engineIntelligenceStatus) { this.engineIntelligenceStatus = engineIntelligenceStatus; }
    public EngineIntelligenceAgreementStatus getAgreementStatus() { return agreementStatus; }
    public void setAgreementStatus(EngineIntelligenceAgreementStatus agreementStatus) { this.agreementStatus = agreementStatus; }
    public EngineIntelligenceRiskMismatchStatus getRiskMismatchStatus() { return riskMismatchStatus; }
    public void setRiskMismatchStatus(EngineIntelligenceRiskMismatchStatus riskMismatchStatus) { this.riskMismatchStatus = riskMismatchStatus; }
    public EngineIntelligenceScoreDeltaBucket getScoreDeltaBucket() { return scoreDeltaBucket; }
    public void setScoreDeltaBucket(EngineIntelligenceScoreDeltaBucket scoreDeltaBucket) { this.scoreDeltaBucket = scoreDeltaBucket; }
    public AnalystRecommendationStatus getAnalystRecommendationStatus() { return analystRecommendationStatus; }
    public void setAnalystRecommendationStatus(AnalystRecommendationStatus analystRecommendationStatus) { this.analystRecommendationStatus = analystRecommendationStatus; }
    public AnalystRecommendation getAnalystRecommendation() { return analystRecommendation; }
    public void setAnalystRecommendation(AnalystRecommendation analystRecommendation) { this.analystRecommendation = analystRecommendation; }
    public String getAnalystRecommendationVersion() { return analystRecommendationVersion; }
    public void setAnalystRecommendationVersion(String analystRecommendationVersion) { this.analystRecommendationVersion = analystRecommendationVersion; }
    public Instant getAnalystRecommendationGeneratedAt() { return analystRecommendationGeneratedAt; }
    public void setAnalystRecommendationGeneratedAt(Instant analystRecommendationGeneratedAt) { this.analystRecommendationGeneratedAt = analystRecommendationGeneratedAt; }
    public List<String> getAnalystRecommendationReasonCodes() { return analystRecommendationReasonCodes; }
    public void setAnalystRecommendationReasonCodes(List<String> analystRecommendationReasonCodes) { this.analystRecommendationReasonCodes = analystRecommendationReasonCodes; }
}
