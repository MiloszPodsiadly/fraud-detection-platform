package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.alert.audit.AuditEventDocument;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;

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
        AuditEvidenceExportAnchorReference externalAnchor
) {
    static AuditEvidenceExportEvent from(
            AuditEventDocument document,
            AuditEvidenceExportAnchorReference localAnchor,
            AuditEvidenceExportAnchorReference externalAnchor
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
                externalAnchor
        );
    }
}
