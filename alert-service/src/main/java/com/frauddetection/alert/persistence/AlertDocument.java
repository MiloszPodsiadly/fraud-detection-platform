package com.frauddetection.alert.persistence;

import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.contract.FraudDecisionEvent;
import com.frauddetection.common.events.model.CustomerContext;
import com.frauddetection.common.events.model.DeviceInfo;
import com.frauddetection.common.events.model.LocationInfo;
import com.frauddetection.common.events.model.MerchantInfo;
import com.frauddetection.common.events.model.Money;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Document(collection = "alerts")
public class AlertDocument {

    @Id
    private String alertId;

    @Indexed(unique = true)
    private String transactionId;

    private String customerId;
    private String correlationId;
    private Instant createdAt;
    private Instant alertTimestamp;
    private RiskLevel riskLevel;
    private Double fraudScore;
    private AlertStatus alertStatus;
    private String alertReason;
    private List<String> reasonCodes;
    private Money transactionAmount;
    private MerchantInfo merchantInfo;
    private DeviceInfo deviceInfo;
    private LocationInfo locationInfo;
    private CustomerContext customerContext;
    private Map<String, Object> scoreDetails;
    private Map<String, Object> featureSnapshot;
    private AnalystDecision analystDecision;
    private String analystId;
    private String decisionReason;
    private List<String> decisionTags;
    private Instant decidedAt;
    private String decisionIdempotencyKey;
    private String decisionIdempotencyRequestHash;
    private String decisionOperationStatus;
    private FraudDecisionEvent decisionOutboxEvent;
    private String decisionOutboxStatus;
    private String decisionOutboxLeaseOwner;
    private Instant decisionOutboxLeaseExpiresAt;
    private int decisionOutboxAttempts;
    private Instant decisionOutboxLastAttemptAt;
    private Instant decisionOutboxPublishedAt;
    private String decisionOutboxLastError;
    private String decisionOutboxFailureReason;

    public String getAlertId() { return alertId; }
    public void setAlertId(String alertId) { this.alertId = alertId; }
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getAlertTimestamp() { return alertTimestamp; }
    public void setAlertTimestamp(Instant alertTimestamp) { this.alertTimestamp = alertTimestamp; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public void setRiskLevel(RiskLevel riskLevel) { this.riskLevel = riskLevel; }
    public Double getFraudScore() { return fraudScore; }
    public void setFraudScore(Double fraudScore) { this.fraudScore = fraudScore; }
    public AlertStatus getAlertStatus() { return alertStatus; }
    public void setAlertStatus(AlertStatus alertStatus) { this.alertStatus = alertStatus; }
    public String getAlertReason() { return alertReason; }
    public void setAlertReason(String alertReason) { this.alertReason = alertReason; }
    public List<String> getReasonCodes() { return reasonCodes; }
    public void setReasonCodes(List<String> reasonCodes) { this.reasonCodes = reasonCodes; }
    public Money getTransactionAmount() { return transactionAmount; }
    public void setTransactionAmount(Money transactionAmount) { this.transactionAmount = transactionAmount; }
    public MerchantInfo getMerchantInfo() { return merchantInfo; }
    public void setMerchantInfo(MerchantInfo merchantInfo) { this.merchantInfo = merchantInfo; }
    public DeviceInfo getDeviceInfo() { return deviceInfo; }
    public void setDeviceInfo(DeviceInfo deviceInfo) { this.deviceInfo = deviceInfo; }
    public LocationInfo getLocationInfo() { return locationInfo; }
    public void setLocationInfo(LocationInfo locationInfo) { this.locationInfo = locationInfo; }
    public CustomerContext getCustomerContext() { return customerContext; }
    public void setCustomerContext(CustomerContext customerContext) { this.customerContext = customerContext; }
    public Map<String, Object> getScoreDetails() { return scoreDetails; }
    public void setScoreDetails(Map<String, Object> scoreDetails) { this.scoreDetails = scoreDetails; }
    public Map<String, Object> getFeatureSnapshot() { return featureSnapshot; }
    public void setFeatureSnapshot(Map<String, Object> featureSnapshot) { this.featureSnapshot = featureSnapshot; }
    public AnalystDecision getAnalystDecision() { return analystDecision; }
    public void setAnalystDecision(AnalystDecision analystDecision) { this.analystDecision = analystDecision; }
    public String getAnalystId() { return analystId; }
    public void setAnalystId(String analystId) { this.analystId = analystId; }
    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }
    public List<String> getDecisionTags() { return decisionTags; }
    public void setDecisionTags(List<String> decisionTags) { this.decisionTags = decisionTags; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
    public String getDecisionIdempotencyKey() { return decisionIdempotencyKey; }
    public void setDecisionIdempotencyKey(String decisionIdempotencyKey) { this.decisionIdempotencyKey = decisionIdempotencyKey; }
    public String getDecisionIdempotencyRequestHash() { return decisionIdempotencyRequestHash; }
    public void setDecisionIdempotencyRequestHash(String decisionIdempotencyRequestHash) { this.decisionIdempotencyRequestHash = decisionIdempotencyRequestHash; }
    public String getDecisionOperationStatus() { return decisionOperationStatus; }
    public void setDecisionOperationStatus(String decisionOperationStatus) { this.decisionOperationStatus = decisionOperationStatus; }
    public FraudDecisionEvent getDecisionOutboxEvent() { return decisionOutboxEvent; }
    public void setDecisionOutboxEvent(FraudDecisionEvent decisionOutboxEvent) { this.decisionOutboxEvent = decisionOutboxEvent; }
    public String getDecisionOutboxStatus() { return decisionOutboxStatus; }
    public void setDecisionOutboxStatus(String decisionOutboxStatus) { this.decisionOutboxStatus = decisionOutboxStatus; }
    public String getDecisionOutboxLeaseOwner() { return decisionOutboxLeaseOwner; }
    public void setDecisionOutboxLeaseOwner(String decisionOutboxLeaseOwner) { this.decisionOutboxLeaseOwner = decisionOutboxLeaseOwner; }
    public Instant getDecisionOutboxLeaseExpiresAt() { return decisionOutboxLeaseExpiresAt; }
    public void setDecisionOutboxLeaseExpiresAt(Instant decisionOutboxLeaseExpiresAt) { this.decisionOutboxLeaseExpiresAt = decisionOutboxLeaseExpiresAt; }
    public int getDecisionOutboxAttempts() { return decisionOutboxAttempts; }
    public void setDecisionOutboxAttempts(int decisionOutboxAttempts) { this.decisionOutboxAttempts = decisionOutboxAttempts; }
    public Instant getDecisionOutboxLastAttemptAt() { return decisionOutboxLastAttemptAt; }
    public void setDecisionOutboxLastAttemptAt(Instant decisionOutboxLastAttemptAt) { this.decisionOutboxLastAttemptAt = decisionOutboxLastAttemptAt; }
    public Instant getDecisionOutboxPublishedAt() { return decisionOutboxPublishedAt; }
    public void setDecisionOutboxPublishedAt(Instant decisionOutboxPublishedAt) { this.decisionOutboxPublishedAt = decisionOutboxPublishedAt; }
    public String getDecisionOutboxLastError() { return decisionOutboxLastError; }
    public void setDecisionOutboxLastError(String decisionOutboxLastError) { this.decisionOutboxLastError = decisionOutboxLastError; }
    public String getDecisionOutboxFailureReason() { return decisionOutboxFailureReason; }
    public void setDecisionOutboxFailureReason(String decisionOutboxFailureReason) { this.decisionOutboxFailureReason = decisionOutboxFailureReason; }
}
