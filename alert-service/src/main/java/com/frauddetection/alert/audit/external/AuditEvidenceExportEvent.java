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

        @JsonProperty("trust_level")
        String trustLevel,

        @JsonProperty("integrity_status")
        String integrityStatus,

        @JsonProperty("signature_policy")
        String signaturePolicy,

        @JsonProperty("signature_status")
        String signatureStatus,

        @JsonProperty("evidence_source")
        String evidenceSource,

        @JsonProperty("confidence")
        String confidence,

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
                trustLevel(externalAnchor),
                integrityStatus(externalAnchor, semantics),
                signaturePolicy(externalAnchor),
                signatureStatus(externalAnchor),
                evidenceSource(localAnchor, externalAnchor),
                confidence(externalAnchor),
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

    private static String trustLevel(AuditEvidenceExportAnchorReference externalAnchor) {
        if (externalAnchor == null) {
            return "LOCAL_ONLY";
        }
        return "SIGNED".equals(externalAnchor.signatureStatus()) ? "EXTERNAL_SIGNED" : "EXTERNAL_UNSIGNED";
    }

    private static String integrityStatus(
            AuditEvidenceExportAnchorReference externalAnchor,
            AuditEventBusinessSemantics semantics
    ) {
        if (semantics.auditEvidenceStatus() == AuditEvidenceStatus.ANCHOR_REQUIRED_FAILED) {
            return "INVALID";
        }
        return externalAnchor == null ? "PARTIAL" : "VALID";
    }

    private static String signaturePolicy(AuditEvidenceExportAnchorReference externalAnchor) {
        return externalAnchor != null && "SIGNED".equals(externalAnchor.signatureStatus())
                ? "REQUIRED_FOR_TRUST"
                : "OPTIONAL";
    }

    private static String signatureStatus(AuditEvidenceExportAnchorReference externalAnchor) {
        if (externalAnchor == null || externalAnchor.signatureStatus() == null) {
            return "UNSIGNED";
        }
        return externalAnchor.signatureStatus();
    }

    private static String evidenceSource(
            AuditEvidenceExportAnchorReference localAnchor,
            AuditEvidenceExportAnchorReference externalAnchor
    ) {
        if (externalAnchor != null) {
            return externalAnchor.signatureStatus() == null ? "EXTERNAL_WITNESS" : "ARTIFACT_VERIFIER";
        }
        return localAnchor == null ? "PUBLICATION_STATUS_REPO" : "LOCAL_CHAIN";
    }

    private static String confidence(AuditEvidenceExportAnchorReference externalAnchor) {
        if (externalAnchor == null) {
            return "STRUCTURAL_VALID";
        }
        return "SIGNED".equals(externalAnchor.signatureStatus())
                ? "EXTERNALLY_ANCHORED_SIGNED"
                : "EXTERNALLY_ANCHORED_UNSIGNED";
    }
}
