package com.frauddetection.alert.audit.read;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.IndexDirection;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.List;

@Document(collection = "read_access_audit_events")
@CompoundIndexes({
        @CompoundIndex(name = "read_access_actor_created_at_idx", def = "{'actor_id': 1, 'created_at': -1}"),
        @CompoundIndex(name = "read_access_endpoint_created_at_idx", def = "{'endpoint_category': 1, 'created_at': -1}"),
        @CompoundIndex(name = "read_access_resource_created_at_idx", def = "{'resource_type': 1, 'resource_id': 1, 'created_at': -1}")
})
public record ReadAccessAuditEventDocument(
        @Id
        @Field("audit_id")
        String auditId,

        @Field("occurred_at")
        @Indexed(name = "read_access_occurred_at_idx", direction = IndexDirection.DESCENDING)
        Instant occurredAt,

        @Field("created_at")
        @Indexed(name = "read_access_created_at_idx", direction = IndexDirection.DESCENDING)
        Instant createdAt,

        @Field("actor_id")
        String actorId,

        @Field("actor_roles")
        List<String> actorRoles,

        @Field("action")
        ReadAccessAuditAction action,

        @Field("resource_type")
        ReadAccessResourceType resourceType,

        @Field("resource_id")
        String resourceId,

        @Field("endpoint_category")
        ReadAccessEndpointCategory endpointCategory,

        @Field("query_hash")
        String queryHash,

        @Field("filter_bucket")
        String filterBucket,

        @Field("page")
        Integer page,

        @Field("size")
        Integer size,

        @Field("result_count")
        int resultCount,

        @Field("outcome")
        ReadAccessAuditOutcome outcome,

        @Field("correlation_id")
        String correlationId,

        @Field("source_service")
        String sourceService,

        @Field("schema_version")
        int schemaVersion
) {
    static ReadAccessAuditEventDocument from(ReadAccessAuditEvent event) {
        return new ReadAccessAuditEventDocument(
                event.auditId(),
                event.occurredAt(),
                event.occurredAt(),
                event.actorId(),
                event.actorRoles().stream().sorted().toList(),
                event.action(),
                event.resourceType(),
                event.resourceId(),
                event.endpointCategory(),
                event.queryHash(),
                event.filterBucket(),
                event.page(),
                event.size(),
                event.resultCount(),
                event.outcome(),
                event.correlationId(),
                event.sourceService(),
                event.schemaVersion()
        );
    }
}
