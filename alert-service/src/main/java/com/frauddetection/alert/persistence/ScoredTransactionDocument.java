package com.frauddetection.alert.persistence;

import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.model.MerchantInfo;
import com.frauddetection.common.events.model.Money;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "scored_transactions")
public class ScoredTransactionDocument {

    @Id
    private String transactionId;

    private String customerId;
    private String correlationId;
    private Instant transactionTimestamp;

    @Indexed
    private Instant scoredAt;

    private Money transactionAmount;
    private MerchantInfo merchantInfo;
    private Double fraudScore;
    private RiskLevel riskLevel;
    private Boolean alertRecommended;
    private List<String> reasonCodes;

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public Instant getTransactionTimestamp() { return transactionTimestamp; }
    public void setTransactionTimestamp(Instant transactionTimestamp) { this.transactionTimestamp = transactionTimestamp; }
    public Instant getScoredAt() { return scoredAt; }
    public void setScoredAt(Instant scoredAt) { this.scoredAt = scoredAt; }
    public Money getTransactionAmount() { return transactionAmount; }
    public void setTransactionAmount(Money transactionAmount) { this.transactionAmount = transactionAmount; }
    public MerchantInfo getMerchantInfo() { return merchantInfo; }
    public void setMerchantInfo(MerchantInfo merchantInfo) { this.merchantInfo = merchantInfo; }
    public Double getFraudScore() { return fraudScore; }
    public void setFraudScore(Double fraudScore) { this.fraudScore = fraudScore; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public void setRiskLevel(RiskLevel riskLevel) { this.riskLevel = riskLevel; }
    public Boolean getAlertRecommended() { return alertRecommended; }
    public void setAlertRecommended(Boolean alertRecommended) { this.alertRecommended = alertRecommended; }
    public List<String> getReasonCodes() { return reasonCodes; }
    public void setReasonCodes(List<String> reasonCodes) { this.reasonCodes = reasonCodes; }
}
