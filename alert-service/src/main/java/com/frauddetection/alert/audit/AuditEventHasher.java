package com.frauddetection.alert.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

final class AuditEventHasher {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private AuditEventHasher() {
    }

    static String hash(AuditEventDocument document) {
        try {
            MessageDigest digest = MessageDigest.getInstance(AuditEventDocument.HASH_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(canonicalJson(document).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Audit hash algorithm is unavailable.");
        }
    }

    static boolean matches(AuditEventDocument document) {
        return document.eventHash() != null && document.eventHash().equals(hash(document.withEventHash(null)));
    }

    private static String canonicalJson(AuditEventDocument document) {
        try {
            return OBJECT_MAPPER.writeValueAsString(canonicalMap(document));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Audit event canonicalization failed.");
        }
    }

    private static Map<String, Object> canonicalMap(AuditEventDocument document) {
        Map<String, Object> values = new TreeMap<>();
        values.put("audit_event_id", document.auditId());
        values.put("occurred_at", instant(document.createdAt()));
        values.put("actor_id", document.actorId());
        values.put("actor_display_name", document.actorDisplayName());
        values.put("actor_roles", document.actorRoles());
        values.put("actor_type", document.actorType());
        values.put("event_type", enumName(document.eventType()));
        values.put("action", enumName(document.action()));
        values.put("outcome", enumName(document.outcome()));
        values.put("resource_type", enumName(document.resourceType()));
        values.put("resource_id", document.resourceId());
        values.put("correlation_id", document.correlationId());
        values.put("source_service", document.sourceService());
        values.put("request_id", document.requestId());
        values.put("metadata_summary", metadata(document.metadataSummary()));
        values.put("previous_event_hash", document.previousEventHash());
        values.put("hash_algorithm", document.hashAlgorithm());
        values.put("schema_version", document.schemaVersion());
        return values;
    }

    private static Map<String, Object> metadata(AuditEventMetadataSummary metadataSummary) {
        if (metadataSummary == null) {
            return null;
        }
        Map<String, Object> values = new TreeMap<>();
        values.put("correlation_id", metadataSummary.correlationId());
        values.put("request_id", metadataSummary.requestId());
        values.put("source_service", metadataSummary.sourceService());
        values.put("schema_version", metadataSummary.schemaVersion());
        values.put("failure_category", metadataSummary.failureCategory());
        values.put("failure_reason", metadataSummary.failureReason());
        values.put("endpoint_action", metadataSummary.endpointAction());
        values.put("filters_summary", metadataSummary.filtersSummary());
        values.put("count_returned", metadataSummary.countReturned());
        return values;
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String instant(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MILLIS).toString();
    }
}
