package com.frauddetection.alert.audit;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.IndexDirection;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.List;

@Document(collection = "audit_events")
@CompoundIndexes({
        @CompoundIndex(name = "audit_actor_created_at_idx", def = "{'actor_id': 1, 'created_at': -1}"),
        @CompoundIndex(name = "audit_event_type_created_at_idx", def = "{'event_type': 1, 'created_at': -1}"),
        @CompoundIndex(name = "audit_resource_created_at_idx", def = "{'resource_type': 1, 'resource_id': 1, 'created_at': -1}")
})
public record AuditEventDocument(
        @Id
        @Field("audit_id")
        String auditId,

        @Field("event_type")
        AuditAction eventType,

        @Field("actor_id")
        String actorId,

        @Field("actor_display_name")
        String actorDisplayName,

        @Field("actor_roles")
        List<String> actorRoles,

        @Field("actor_authorities")
        List<String> actorAuthorities,

        @Field("action")
        AuditAction action,

        @Field("resource_type")
        AuditResourceType resourceType,

        @Field("resource_id")
        String resourceId,

        @Field("created_at")
        @Indexed(name = "audit_created_at_idx", direction = IndexDirection.DESCENDING)
        Instant createdAt,

        @Field("correlation_id")
        String correlationId,

        @Field("request_id")
        String requestId,

        @Field("source_service")
        String sourceService,

        @Field("outcome")
        AuditOutcome outcome,

        @Field("failure_category")
        AuditFailureCategory failureCategory,

        @Field("failure_reason")
        String failureReason,

        @Field("schema_version")
        String schemaVersion
) {
    private static final String SCHEMA_VERSION = "1.0";
    private static final String SOURCE_SERVICE = "alert-service";
    private static final int MAX_REQUEST_ID_LENGTH = 120;

    static AuditEventDocument from(String auditId, AuditEvent event) {
        return new AuditEventDocument(
                auditId,
                event.action(),
                event.actor().userId(),
                event.actor().userId(),
                sorted(event.actor().roles()),
                sorted(event.actor().authorities()),
                event.action(),
                event.resourceType(),
                event.resourceId(),
                event.timestamp(),
                event.correlationId(),
                bounded(event.requestId(), MAX_REQUEST_ID_LENGTH),
                SOURCE_SERVICE,
                event.outcome(),
                event.failureCategory(),
                event.failureReason(),
                SCHEMA_VERSION
        );
    }

    private static List<String> sorted(java.util.Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replaceAll("[\\r\\n\\t]+", " ");
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
