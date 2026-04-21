package com.frauddetection.alert.persistence;

import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.model.Money;

import java.math.BigDecimal;
import java.time.Instant;

public class FraudCaseTransactionDocument {

    private String transactionId;
    private String correlationId;
    private Instant transactionTimestamp;
    private Money transactionAmount;
    private BigDecimal amountPln;
    private Double fraudScore;
    private RiskLevel riskLevel;

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public Instant getTransactionTimestamp() { return transactionTimestamp; }
    public void setTransactionTimestamp(Instant transactionTimestamp) { this.transactionTimestamp = transactionTimestamp; }
    public Money getTransactionAmount() { return transactionAmount; }
    public void setTransactionAmount(Money transactionAmount) { this.transactionAmount = transactionAmount; }
    public BigDecimal getAmountPln() { return amountPln; }
    public void setAmountPln(BigDecimal amountPln) { this.amountPln = amountPln; }
    public Double getFraudScore() { return fraudScore; }
    public void setFraudScore(Double fraudScore) { this.fraudScore = fraudScore; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public void setRiskLevel(RiskLevel riskLevel) { this.riskLevel = riskLevel; }
}
