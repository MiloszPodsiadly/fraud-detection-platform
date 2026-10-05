package com.frauddetection.alert.engineintelligence;

import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "engine_intelligence_pending_projections")
@CompoundIndex(name = "ei_pending_status_available_idx", def = "{'status': 1, 'availableAt': 1}")
@CompoundIndex(name = "ei_pending_status_lease_idx", def = "{'status': 1, 'leaseExpiresAt': 1}")
public class EngineIntelligencePendingProjection {

    @Id
    private String sourceEventId;
    private String transactionId;
    private Instant sourceEventCreatedAt;
    private String sourceEventFingerprint;
    private EngineIntelligenceSummary engineIntelligence;
    private EngineIntelligenceRecoveryProvenance recoveryProvenance;
    private EngineIntelligencePendingProjectionStatus status;
    private int attempts;
    private Instant availableAt;
    private Instant createdAt;
    private Instant updatedAt;
    private String leaseToken;
    private Instant leaseExpiresAt;
    private String lastReason;

    public String getSourceEventId() { return sourceEventId; }
    public void setSourceEventId(String sourceEventId) { this.sourceEventId = sourceEventId; }
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public Instant getSourceEventCreatedAt() { return sourceEventCreatedAt; }
    public void setSourceEventCreatedAt(Instant sourceEventCreatedAt) { this.sourceEventCreatedAt = sourceEventCreatedAt; }
    public String getSourceEventFingerprint() { return sourceEventFingerprint; }
    public void setSourceEventFingerprint(String sourceEventFingerprint) { this.sourceEventFingerprint = sourceEventFingerprint; }
    public EngineIntelligenceSummary getEngineIntelligence() { return engineIntelligence; }
    public void setEngineIntelligence(EngineIntelligenceSummary engineIntelligence) { this.engineIntelligence = engineIntelligence; }
    public EngineIntelligenceRecoveryProvenance getRecoveryProvenance() { return recoveryProvenance; }
    public void setRecoveryProvenance(EngineIntelligenceRecoveryProvenance recoveryProvenance) { this.recoveryProvenance = recoveryProvenance; }
    public EngineIntelligencePendingProjectionStatus getStatus() { return status; }
    public void setStatus(EngineIntelligencePendingProjectionStatus status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public Instant getAvailableAt() { return availableAt; }
    public void setAvailableAt(Instant availableAt) { this.availableAt = availableAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getLeaseToken() { return leaseToken; }
    public void setLeaseToken(String leaseToken) { this.leaseToken = leaseToken; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public String getLastReason() { return lastReason; }
    public void setLastReason(String lastReason) { this.lastReason = lastReason; }
}
