package com.frauddetection.alert.audit;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "audit_degradation_events")
public class AuditDegradationEventDocument {

    public static final String TYPE_POST_COMMIT_DEGRADED = "POST_COMMIT_DEGRADED";

    @Id
    private String auditId;

    @Indexed
    private String type;

    @Indexed
    private String resourceType;

    @Indexed
    private String resourceId;

    private String operation;
    private Instant timestamp;

    @Indexed
    private boolean resolved;

    @Indexed
    private boolean resolutionPending;

    private Instant resolutionRequestedAt;
    private String resolutionRequestedBy;
    private String resolutionRequestReason;
    private Instant resolvedAt;
    private String resolvedBy;
    private String resolutionReason;
    private String resolutionEvidenceType;
    private String resolutionEvidenceReference;
    private Instant resolutionEvidenceVerifiedAt;
    private String resolutionEvidenceVerifiedBy;
    private Instant approvedAt;
    private String approvedBy;
    private String approvalReason;
    private String reason;

    public String getAuditId() {
        return auditId;
    }

    public void setAuditId(String auditId) {
        this.auditId = auditId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public boolean isResolved() {
        return resolved;
    }

    public void setResolved(boolean resolved) {
        this.resolved = resolved;
    }

    public boolean isResolutionPending() {
        return resolutionPending;
    }

    public void setResolutionPending(boolean resolutionPending) {
        this.resolutionPending = resolutionPending;
    }

    public Instant getResolutionRequestedAt() {
        return resolutionRequestedAt;
    }

    public void setResolutionRequestedAt(Instant resolutionRequestedAt) {
        this.resolutionRequestedAt = resolutionRequestedAt;
    }

    public String getResolutionRequestedBy() {
        return resolutionRequestedBy;
    }

    public void setResolutionRequestedBy(String resolutionRequestedBy) {
        this.resolutionRequestedBy = resolutionRequestedBy;
    }

    public String getResolutionRequestReason() {
        return resolutionRequestReason;
    }

    public void setResolutionRequestReason(String resolutionRequestReason) {
        this.resolutionRequestReason = resolutionRequestReason;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(String resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public String getResolutionReason() {
        return resolutionReason;
    }

    public void setResolutionReason(String resolutionReason) {
        this.resolutionReason = resolutionReason;
    }

    public String getResolutionEvidenceType() {
        return resolutionEvidenceType;
    }

    public void setResolutionEvidenceType(String resolutionEvidenceType) {
        this.resolutionEvidenceType = resolutionEvidenceType;
    }

    public String getResolutionEvidenceReference() {
        return resolutionEvidenceReference;
    }

    public void setResolutionEvidenceReference(String resolutionEvidenceReference) {
        this.resolutionEvidenceReference = resolutionEvidenceReference;
    }

    public Instant getResolutionEvidenceVerifiedAt() {
        return resolutionEvidenceVerifiedAt;
    }

    public void setResolutionEvidenceVerifiedAt(Instant resolutionEvidenceVerifiedAt) {
        this.resolutionEvidenceVerifiedAt = resolutionEvidenceVerifiedAt;
    }

    public String getResolutionEvidenceVerifiedBy() {
        return resolutionEvidenceVerifiedBy;
    }

    public void setResolutionEvidenceVerifiedBy(String resolutionEvidenceVerifiedBy) {
        this.resolutionEvidenceVerifiedBy = resolutionEvidenceVerifiedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public String getApprovalReason() {
        return approvalReason;
    }

    public void setApprovalReason(String approvalReason) {
        this.approvalReason = approvalReason;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
