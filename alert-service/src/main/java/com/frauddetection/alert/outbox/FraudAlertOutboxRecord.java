package com.frauddetection.alert.outbox;

import com.frauddetection.common.events.contract.FraudAlertEvent;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "fraud_alert_outbox_records")
@CompoundIndex(name = "alert_outbox_status_created_idx", def = "{'status': 1, 'createdAt': 1}")
public class FraudAlertOutboxRecord {

    @Id
    private String eventId;

    @Indexed(unique = true)
    private String alertId;

    private String transactionId;
    private FraudAlertEvent payload;
    private FraudAlertOutboxStatus status;
    private int attempts;
    private String leaseOwner;
    private String leaseToken;
    private Instant leaseExpiresAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant publishedAt;
    private String lastError;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getAlertId() { return alertId; }
    public void setAlertId(String alertId) { this.alertId = alertId; }
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public FraudAlertEvent getPayload() { return payload; }
    public void setPayload(FraudAlertEvent payload) { this.payload = payload; }
    public FraudAlertOutboxStatus getStatus() { return status; }
    public void setStatus(FraudAlertOutboxStatus status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public String getLeaseToken() { return leaseToken; }
    public void setLeaseToken(String leaseToken) { this.leaseToken = leaseToken; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
