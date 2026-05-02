package com.frauddetection.alert.trust;

import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.List;

@Document(collection = "trust_incidents")
@CompoundIndex(name = "incident_dedupe_idx", def = "{'type': 1, 'source': 1, 'fingerprint': 1, 'status': 1}")
public class TrustIncidentDocument {

    @Id
    @Field("incident_id")
    private String incidentId;
    private String type;
    private TrustIncidentSeverity severity;
    private String source;
    private String fingerprint;
    private TrustIncidentStatus status;
    @Field("first_seen_at")
    private Instant firstSeenAt;
    @Field("last_seen_at")
    private Instant lastSeenAt;
    @Field("occurrence_count")
    private long occurrenceCount;
    @Field("evidence_refs")
    private List<String> evidenceRefs;
    @Field("acknowledged_by")
    private String acknowledgedBy;
    @Field("acknowledged_at")
    private Instant acknowledgedAt;
    @Field("resolved_by")
    private String resolvedBy;
    @Field("resolved_at")
    private Instant resolvedAt;
    @Field("resolution_reason")
    private String resolutionReason;
    @Field("resolution_evidence")
    private ResolutionEvidenceReference resolutionEvidence;
    @Field("created_at")
    private Instant createdAt;
    @Field("updated_at")
    private Instant updatedAt;

    public String getIncidentId() { return incidentId; }
    public void setIncidentId(String incidentId) { this.incidentId = incidentId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public TrustIncidentSeverity getSeverity() { return severity; }
    public void setSeverity(TrustIncidentSeverity severity) { this.severity = severity; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public TrustIncidentStatus getStatus() { return status; }
    public void setStatus(TrustIncidentStatus status) { this.status = status; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public long getOccurrenceCount() { return occurrenceCount; }
    public void setOccurrenceCount(long occurrenceCount) { this.occurrenceCount = occurrenceCount; }
    public List<String> getEvidenceRefs() { return evidenceRefs; }
    public void setEvidenceRefs(List<String> evidenceRefs) { this.evidenceRefs = evidenceRefs; }
    public String getAcknowledgedBy() { return acknowledgedBy; }
    public void setAcknowledgedBy(String acknowledgedBy) { this.acknowledgedBy = acknowledgedBy; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public void setAcknowledgedAt(Instant acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }
    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public String getResolutionReason() { return resolutionReason; }
    public void setResolutionReason(String resolutionReason) { this.resolutionReason = resolutionReason; }
    public ResolutionEvidenceReference getResolutionEvidence() { return resolutionEvidence; }
    public void setResolutionEvidence(ResolutionEvidenceReference resolutionEvidence) { this.resolutionEvidence = resolutionEvidence; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
