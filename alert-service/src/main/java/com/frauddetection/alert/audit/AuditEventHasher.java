package com.frauddetection.alert.audit;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class AuditEventHasher {

    private static final Set<String> HASHED_DOCUMENT_COMPONENTS = Set.of(
            "auditId", "eventType", "actorId", "actorDisplayName", "actorRoles", "actorType",
            "actorAuthorities", "action", "resourceType", "resourceId", "createdAt", "correlationId",
            "requestId", "sourceService", "partitionKey", "chainPosition", "outcome", "failureCategory",
            "failureReason", "metadataSummary", "previousEventHash", "hashAlgorithm", "schemaVersion"
    );
    private static final Set<String> HASHED_METADATA_COMPONENTS = Set.of(
            "correlationId", "requestId", "sourceService", "schemaVersion", "failureCategory",
            "failureReason", "endpointAction", "filtersSummary", "countReturned", "from", "to", "limit",
            "returnedCount", "exportStatus", "reasonCode", "externalAnchorStatus", "anchorCoverage",
            "exportFingerprint", "trustLevel", "internalIntegrityStatus", "externalIntegrityStatus",
            "attestationFingerprint"
    );

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private AuditEventHasher() {
    }

    static String hash(AuditEventDocument document) {
        try {
            MessageDigest digest = MessageDigest.getInstance(AuditEventDocument.HASH_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(canonicalBytes(document)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Audit hash algorithm is unavailable.");
        }
    }

    public static boolean matches(AuditEventDocument document) {
        return document.eventHash() != null && document.eventHash().equals(hash(document.withEventHash(null)));
    }

    static Set<String> hashedDocumentComponents() {
        return HASHED_DOCUMENT_COMPONENTS;
    }

    static Set<String> hashedMetadataComponents() {
        return HASHED_METADATA_COMPONENTS;
    }

    static byte[] canonicalBytes(AuditEventDocument document) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(canonicalMap(document));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Audit event canonicalization failed.");
        }
    }

    private static Map<String, Object> canonicalMap(AuditEventDocument document) {
        Map<String, Object> values = new TreeMap<>();
        values.put("audit_event_id", document.auditId());
        values.put("occurred_at", instant(document.createdAt()));
        values.put("actor_id", document.actorId());
        values.put("actor_display_name", document.actorDisplayName());
        values.put("actor_roles", sorted(document.actorRoles()));
        values.put("actor_type", document.actorType());
        values.put("actor_authorities", sorted(document.actorAuthorities()));
        values.put("event_type", enumName(document.eventType()));
        values.put("action", enumName(document.action()));
        values.put("outcome", enumName(document.outcome()));
        values.put("resource_type", enumName(document.resourceType()));
        values.put("resource_id", document.resourceId());
        values.put("correlation_id", document.correlationId());
        values.put("source_service", document.sourceService());
        values.put("partition_key", document.partitionKey());
        values.put("chain_position", document.chainPosition());
        values.put("request_id", document.requestId());
        values.put("failure_category", enumName(document.failureCategory()));
        values.put("failure_reason", document.failureReason());
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
        values.put("from", metadataSummary.from());
        values.put("to", metadataSummary.to());
        values.put("limit", metadataSummary.limit());
        values.put("returned_count", metadataSummary.returnedCount());
        values.put("export_status", metadataSummary.exportStatus());
        values.put("reason_code", metadataSummary.reasonCode());
        values.put("external_anchor_status", metadataSummary.externalAnchorStatus());
        values.put("anchor_coverage", anchorCoverage(metadataSummary.anchorCoverage()));
        values.put("export_fingerprint", metadataSummary.exportFingerprint());
        values.put("trust_level", metadataSummary.trustLevel());
        values.put("internal_integrity_status", metadataSummary.internalIntegrityStatus());
        values.put("external_integrity_status", metadataSummary.externalIntegrityStatus());
        values.put("attestation_fingerprint", metadataSummary.attestationFingerprint());
        return values;
    }

    private static Map<String, Object> anchorCoverage(AuditEventMetadataSummary.AnchorCoverageSummary coverage) {
        if (coverage == null) {
            return null;
        }
        Map<String, Object> values = new TreeMap<>();
        values.put("total_events", coverage.totalEvents());
        values.put("events_with_local_anchor", coverage.eventsWithLocalAnchor());
        values.put("events_with_external_anchor", coverage.eventsWithExternalAnchor());
        values.put("events_missing_external_anchor", coverage.eventsMissingExternalAnchor());
        values.put("coverage_ratio", BigDecimal.valueOf(coverage.coverageRatio()).stripTrailingZeros());
        return values;
    }

    private static List<String> sorted(List<String> values) {
        return values == null ? null : values.stream()
                .sorted(Comparator.nullsFirst(String::compareTo))
                .toList();
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String instant(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MILLIS).toString();
    }
}
