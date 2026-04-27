package com.frauddetection.alert.audit;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Immutable;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.IndexDirection;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.List;

@Document(collection = "audit_events")
@Immutable
@CompoundIndexes({
        @CompoundIndex(name = "audit_actor_created_at_idx", def = "{'actor_id': 1, 'created_at': -1}"),
        @CompoundIndex(name = "audit_event_type_created_at_idx", def = "{'event_type': 1, 'created_at': -1}"),
        @CompoundIndex(name = "audit_resource_created_at_idx", def = "{'resource_type': 1, 'resource_id': 1, 'created_at': -1}"),
        @CompoundIndex(name = "audit_source_service_created_at_idx", def = "{'source_service': 1, 'created_at': -1}"),
        @CompoundIndex(name = "audit_partition_created_at_idx", def = "{'partition_key': 1, 'created_at': -1}")
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

        @Field("actor_type")
        String actorType,

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

        @Field("partition_key")
        String partitionKey,

        @Field("chain_position")
        Long chainPosition,

        @Field("outcome")
        AuditOutcome outcome,

        @Field("failure_category")
        AuditFailureCategory failureCategory,

        @Field("failure_reason")
        String failureReason,

        @Field("metadata_summary")
        AuditEventMetadataSummary metadataSummary,

        @Field("previous_event_hash")
        String previousEventHash,

        @Field("event_hash")
        String eventHash,

        @Field("hash_algorithm")
        String hashAlgorithm,

        @Field("schema_version")
        String schemaVersion
) {
    private static final String SCHEMA_VERSION = "1.0";
    private static final String SOURCE_SERVICE = "alert-service";
    static final String PARTITION_KEY = "source_service:alert-service";
    static final String HASH_ALGORITHM = "SHA-256";
    private static final int MAX_REQUEST_ID_LENGTH = 120;

    static AuditEventDocument from(String auditId, AuditEvent event, String previousEventHash, long chainPosition) {
        AuditFailureCategory failureCategory = event.failureCategory() == null
                ? AuditEvent.failureCategory(event.outcome(), event.failureReason())
                : event.failureCategory();
        AuditEventMetadataSummary metadataSummary = event.metadataSummary() != null
                ? event.metadataSummary()
                : new AuditEventMetadataSummary(
                event.correlationId(),
                bounded(event.requestId(), MAX_REQUEST_ID_LENGTH),
                SOURCE_SERVICE,
                SCHEMA_VERSION,
                failureCategory.name(),
                event.failureReason(),
                null,
                null,
                null
        );
        return new AuditEventDocument(
                auditId,
                event.action(),
                event.actor().userId(),
                event.actor().userId(),
                sorted(event.actor().roles()),
                "HUMAN",
                sorted(event.actor().authorities()),
                event.action(),
                event.resourceType(),
                event.resourceId(),
                event.timestamp(),
                event.correlationId(),
                bounded(event.requestId(), MAX_REQUEST_ID_LENGTH),
                SOURCE_SERVICE,
                PARTITION_KEY,
                chainPosition,
                event.outcome(),
                failureCategory,
                event.failureReason(),
                metadataSummary,
                previousEventHash,
                null,
                HASH_ALGORITHM,
                SCHEMA_VERSION
        ).withEventHash(AuditEventHasher.hash(thisWithoutHashPlaceholder(
                auditId,
                event,
                previousEventHash,
                chainPosition,
                metadataSummary
        )));
    }

    static AuditEventDocument from(String auditId, AuditEvent event, String previousEventHash) {
        return from(auditId, event, previousEventHash, 1L);
    }

    static AuditEventDocument from(String auditId, AuditEvent event) {
        return from(auditId, event, null);
    }

    AuditEventDocument withEventHash(String eventHash) {
        return new AuditEventDocument(
                auditId,
                eventType,
                actorId,
                actorDisplayName,
                actorRoles,
                actorType,
                actorAuthorities,
                action,
                resourceType,
                resourceId,
                createdAt,
                correlationId,
                requestId,
                sourceService,
                partitionKey,
                chainPosition,
                outcome,
                failureCategory,
                failureReason,
                metadataSummary,
                previousEventHash,
                eventHash,
                hashAlgorithm,
                schemaVersion
        );
    }

    private static AuditEventDocument thisWithoutHashPlaceholder(
            String auditId,
            AuditEvent event,
            String previousEventHash,
            long chainPosition,
            AuditEventMetadataSummary metadataSummary
    ) {
        return new AuditEventDocument(
                auditId,
                event.action(),
                event.actor().userId(),
                event.actor().userId(),
                sorted(event.actor().roles()),
                "HUMAN",
                sorted(event.actor().authorities()),
                event.action(),
                event.resourceType(),
                event.resourceId(),
                event.timestamp(),
                event.correlationId(),
                bounded(event.requestId(), MAX_REQUEST_ID_LENGTH),
                SOURCE_SERVICE,
                PARTITION_KEY,
                chainPosition,
                event.outcome(),
                event.failureCategory() == null
                        ? AuditEvent.failureCategory(event.outcome(), event.failureReason())
                        : event.failureCategory(),
                event.failureReason(),
                metadataSummary,
                previousEventHash,
                null,
                HASH_ALGORITHM,
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
