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

        @Field("outcome")
        AuditOutcome outcome,

        @Field("failure_reason")
        String failureReason
) {
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
                event.outcome(),
                event.failureReason()
        );
    }

    private static List<String> sorted(java.util.Set<String> values) {
        return values.stream().sorted().toList();
    }
}
