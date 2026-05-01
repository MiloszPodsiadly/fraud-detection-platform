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

        @JsonProperty("actor_type")
        String actorType,

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

        @JsonProperty("correlation_id")
        String correlationId,

        @JsonProperty("source_service")
        String sourceService,

        @JsonProperty("partition_key")
        String partitionKey,

        @JsonProperty("chain_position")
        Long chainPosition,

        @JsonProperty("request_id")
        String requestId,

        @JsonProperty("metadata_summary")
        AuditEventMetadataSummary metadataSummary,

        @JsonProperty("previous_event_hash")
        String previousEventHash,

        @JsonProperty("event_hash")
        String eventHash,

        @JsonProperty("hash_algorithm")
        String hashAlgorithm,

        @JsonProperty("schema_version")
        String schemaVersion,

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
    static AuditEventResponse from(AuditEventDocument document) {
        return from(document, AuditEventBusinessSemantics.from(document));
    }

    static AuditEventResponse from(AuditEventDocument document, AuditEventBusinessSemantics semantics) {
        return new AuditEventResponse(
                document.auditId(),
                document.eventType().name(),
                document.actorId(),
                document.actorDisplayName(),
                document.actorRoles(),
                document.actorType(),
                document.resourceType().name(),
                document.resourceId(),
                document.action().name(),
                document.outcome().name(),
                document.createdAt(),
                document.correlationId(),
                document.sourceService(),
                document.partitionKey(),
                document.chainPosition(),
                document.requestId(),
                AuditEventMetadataSummary.from(document),
                document.previousEventHash(),
                document.eventHash(),
                document.hashAlgorithm(),
                document.schemaVersion(),
                semantics.compensated(),
                semantics.supersededByEventId(),
                semantics.businessEffective(),
                semantics.businessEffectiveStatus(),
                semantics.auditEvidenceStatus(),
                semantics.externalAnchorStatus(),
                semantics.compensationType(),
                semantics.relatedEventId()
        );
    }
}
