package com.frauddetection.alert.audit;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record AuditEventResponse(
        @JsonProperty("audit_event_id")
        String auditEventId,

        @JsonProperty("event_type")
        String eventType,

        @JsonProperty("actor_id")
        String actorId,

        @JsonProperty("actor_display_name")
        String actorDisplayName,

        @JsonProperty("actor_roles")
        List<String> actorRoles,

        @JsonProperty("resource_type")
        String resourceType,

        @JsonProperty("resource_id")
        String resourceId,

        @JsonProperty("action")
        String action,

        @JsonProperty("outcome")
        String outcome,

        @JsonProperty("occurred_at")
        Instant occurredAt,

        @JsonProperty("metadata_summary")
        AuditEventMetadataSummary metadataSummary
) {
    static AuditEventResponse from(AuditEventDocument document) {
        return new AuditEventResponse(
                document.auditId(),
                document.eventType().name(),
                document.actorId(),
                document.actorDisplayName(),
                document.actorRoles(),
                document.resourceType().name(),
                document.resourceId(),
                document.action().name(),
                document.outcome().name(),
                document.createdAt(),
                AuditEventMetadataSummary.from(document)
        );
    }
}
