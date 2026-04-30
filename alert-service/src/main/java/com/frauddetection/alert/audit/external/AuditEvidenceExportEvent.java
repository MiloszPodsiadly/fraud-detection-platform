package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.alert.audit.AuditEventBusinessSemantics;
import com.frauddetection.alert.audit.AuditEventDocument;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
import com.frauddetection.alert.audit.AuditEvidenceStatus;
import com.frauddetection.alert.audit.AuditExternalAnchorStatus;
import com.frauddetection.alert.audit.BusinessEffectiveStatus;
import com.frauddetection.alert.audit.CompensationType;

import java.time.Instant;
import java.util.List;

public record AuditEvidenceExportEvent(
        @JsonProperty("audit_event_id")
        String auditEventId,

        @JsonProperty("event_type")
        String eventType,

        @JsonProperty("actor_id")
        String actorId,

        @JsonProperty("actor_roles")
        List<String> actorRoles,

        @JsonProperty("resource_type")
        String resourceType,

        @JsonProperty("resource_id")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String resourceId,

        @JsonProperty("action")
        String action,

        @JsonProperty("outcome")
        String outcome,

        @JsonProperty("occurred_at")
        Instant occurredAt,

        @JsonProperty("metadata_summary")
        AuditEventMetadataSummary metadataSummary,

        @JsonProperty("event_hash")
        String eventHash,

        @JsonProperty("previous_event_hash")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String previousEventHash,

        @JsonProperty("chain_position")
        Long chainPosition,

        @JsonProperty("local_anchor")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        AuditEvidenceExportAnchorReference localAnchor,

        @JsonProperty("external_anchor")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        AuditEvidenceExportAnchorReference externalAnchor,

        @JsonProperty("compensated")
        boolean compensated,

        @JsonProperty("superseded_by_event_id")
        String supersededByEventId,

        @JsonProperty("business_effective")
        boolean businessEffective,

        @JsonProperty("business_effective_status")
        BusinessEffectiveStatus businessEffectiveStatus,

        @JsonProperty("audit_evidence_status")
        AuditEvidenceStatus auditEvidenceStatus,

        @JsonProperty("external_anchor_status")
        AuditExternalAnchorStatus externalAnchorStatus,

        @JsonProperty("compensation_type")
        CompensationType compensationType,

        @JsonProperty("related_event_id")
        String relatedEventId
) {
    static AuditEvidenceExportEvent from(
            AuditEventDocument document,
            AuditEvidenceExportAnchorReference localAnchor,
            AuditEvidenceExportAnchorReference externalAnchor,
            AuditEventBusinessSemantics semantics
    ) {
        return new AuditEvidenceExportEvent(
                document.auditId(),
                document.eventType().name(),
                document.actorId(),
                document.actorRoles(),
                document.resourceType().name(),
                document.resourceId(),
                document.action().name(),
                document.outcome().name(),
                document.createdAt(),
                AuditEventMetadataSummary.from(document),
                document.eventHash(),
                document.previousEventHash(),
                document.chainPosition(),
                localAnchor,
                externalAnchor,
                semantics.compensated(),
                semantics.supersededByEventId(),
                semantics.businessEffective(),
                semantics.businessEffectiveStatus(),
                auditEvidenceStatus(externalAnchor, semantics),
                externalAnchorStatus(externalAnchor, semantics),
                semantics.compensationType(),
                semantics.relatedEventId()
        );
    }

    private static AuditEvidenceStatus auditEvidenceStatus(
            AuditEvidenceExportAnchorReference externalAnchor,
            AuditEventBusinessSemantics semantics
    ) {
        if (semantics.auditEvidenceStatus() == AuditEvidenceStatus.ANCHOR_REQUIRED_FAILED) {
            return AuditEvidenceStatus.ANCHOR_REQUIRED_FAILED;
        }
        return externalAnchor == null ? semantics.auditEvidenceStatus() : AuditEvidenceStatus.EXTERNALLY_ANCHORED;
    }

    private static AuditExternalAnchorStatus externalAnchorStatus(
            AuditEvidenceExportAnchorReference externalAnchor,
            AuditEventBusinessSemantics semantics
    ) {
        if (semantics.externalAnchorStatus() == AuditExternalAnchorStatus.FAILED) {
            return AuditExternalAnchorStatus.FAILED;
        }
        return externalAnchor == null ? AuditExternalAnchorStatus.MISSING : AuditExternalAnchorStatus.PUBLISHED;
    }
}
