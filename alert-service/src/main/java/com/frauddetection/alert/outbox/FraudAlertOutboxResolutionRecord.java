package com.frauddetection.alert.outbox;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "fraud_alert_outbox_resolutions")
@CompoundIndex(name = "fraud_alert_outbox_resolution_event_time_idx", def = "{'eventId': 1, 'resolvedAt': 1}")
public class FraudAlertOutboxResolutionRecord {

    @Id
    private String resolutionId;
    private String eventId;
    private FraudAlertOutboxStatus previousStatus;
    private FraudAlertOutboxConfirmationResolution resolution;
    private String reason;
    private String evidenceType;
    private String evidenceReference;
    private Instant evidenceVerifiedAt;
    private String evidenceVerifiedBy;
    private String resolvedBy;
    private Instant resolvedAt;
    private String requestHash;
    private FraudAlertOutboxRecordResponse responseSnapshot;

    public String getResolutionId() { return resolutionId; }
    public void setResolutionId(String resolutionId) { this.resolutionId = resolutionId; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public FraudAlertOutboxStatus getPreviousStatus() { return previousStatus; }
    public void setPreviousStatus(FraudAlertOutboxStatus previousStatus) { this.previousStatus = previousStatus; }
    public FraudAlertOutboxConfirmationResolution getResolution() { return resolution; }
    public void setResolution(FraudAlertOutboxConfirmationResolution resolution) { this.resolution = resolution; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getEvidenceType() { return evidenceType; }
    public void setEvidenceType(String evidenceType) { this.evidenceType = evidenceType; }
    public String getEvidenceReference() { return evidenceReference; }
    public void setEvidenceReference(String evidenceReference) { this.evidenceReference = evidenceReference; }
    public Instant getEvidenceVerifiedAt() { return evidenceVerifiedAt; }
    public void setEvidenceVerifiedAt(Instant evidenceVerifiedAt) { this.evidenceVerifiedAt = evidenceVerifiedAt; }
    public String getEvidenceVerifiedBy() { return evidenceVerifiedBy; }
    public void setEvidenceVerifiedBy(String evidenceVerifiedBy) { this.evidenceVerifiedBy = evidenceVerifiedBy; }
    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    public FraudAlertOutboxRecordResponse getResponseSnapshot() { return responseSnapshot; }
    public void setResponseSnapshot(FraudAlertOutboxRecordResponse responseSnapshot) { this.responseSnapshot = responseSnapshot; }
}
