package com.frauddetection.alert.audit;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEventHasherTest {

    @Test
    void everyPersistedComponentIsDeliberatelyHashedOrExcluded() {
        Set<String> components = Arrays.stream(AuditEventDocument.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(Collectors.toSet());

        assertThat(components).isEqualTo(union(AuditEventHasher.hashedDocumentComponents(), Set.of("eventHash")));
        assertThat(Arrays.stream(AuditEventMetadataSummary.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(Collectors.toSet()))
                .isEqualTo(AuditEventHasher.hashedMetadataComponents());
    }

    @Test
    void everyPersistedEvidenceComponentChangesTheHash() {
        AuditEventDocument original = document();

        for (String component : AuditEventHasher.hashedDocumentComponents()) {
            assertThat(AuditEventHasher.hash(changed(original, component)))
                    .as(component)
                    .isNotEqualTo(original.eventHash());
        }
        assertThat(AuditEventHasher.hash(original.withEventHash("f".repeat(64))))
                .isEqualTo(original.eventHash());
    }

    @Test
    void everyMetadataComponentChangesTheHash() {
        AuditEventDocument original = document();

        for (String component : AuditEventHasher.hashedMetadataComponents()) {
            AuditEventMetadataSummary changed = changedMetadata(original.metadataSummary(), component);
            assertThat(AuditEventHasher.hash(copy(original, original.actorRoles(), original.actorAuthorities(),
                    original.chainPosition(), changed)))
                    .as(component)
                    .isNotEqualTo(original.eventHash());
        }
    }

    @Test
    void canonicalHashIsStableAcrossCollectionOrderTimestampPrecisionAndMongoRoundTrip() {
        AuditEventDocument original = document();
        AuditEventDocument reordered = copy(original,
                List.of("REVIEWER", "ANALYST"),
                List.of("model:review", "alert:read"),
                original.chainPosition(), original.metadataSummary());

        assertThat(AuditEventHasher.hash(reordered)).isEqualTo(original.eventHash());
        assertThat(original.eventHash()).matches("[0-9a-f]{64}");
        assertThat(original.eventHash()).isEqualTo("dd97940a278060ec7ec8b24503a26e4b3dcbd864ec9e65dc31c631a74d89acfa");

        var conversions = new MongoCustomConversions(List.of());
        var context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        var converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        var bson = new Document();
        converter.write(original, bson);
        AuditEventDocument roundTrip = converter.read(AuditEventDocument.class, bson);

        assertThat(roundTrip.createdAt()).isEqualTo(Instant.parse("2026-10-25T10:15:30.123Z"));
        assertThat(AuditEventHasher.matches(roundTrip)).isTrue();
        assertThat(AuditEventHasher.canonicalBytes(roundTrip)).isEqualTo(AuditEventHasher.canonicalBytes(original));
    }

    @Test
    void nullAndZeroChainPositionsHaveDistinctCanonicalRepresentations() {
        AuditEventDocument original = document();
        AuditEventDocument nullPosition = copy(original, original.actorRoles(), original.actorAuthorities(), null,
                original.metadataSummary());
        AuditEventDocument zeroPosition = copy(original, original.actorRoles(), original.actorAuthorities(), 0L,
                original.metadataSummary());

        assertThat(AuditEventHasher.hash(nullPosition)).isNotEqualTo(AuditEventHasher.hash(zeroPosition));
        assertThat(AuditEventHasher.hash(nullPosition)).isEqualTo(AuditEventHasher.hash(nullPosition));
    }

    private static AuditEventDocument document() {
        AuditEventMetadataSummary metadata = new AuditEventMetadataSummary(
                "meta-correlation", "meta-request", "alert-service", "1.0", "DEPENDENCY", "timeout",
                "POST /decision", "status=open", 3, "2026-10-24T00:00:00Z", "2026-10-25T00:00:00Z",
                50, 3, "COMPLETE", "NONE", "SIGNED",
                new AuditEventMetadataSummary.AnchorCoverageSummary(4, 4, 3, 1, 0.75d),
                "export-fingerprint", "VERIFIED", "INTACT", "WITNESSED", "attestation-fingerprint"
        );
        AuditEventDocument unhashed = new AuditEventDocument(
                "audit-1", AuditAction.SUBMIT_ANALYST_DECISION, "actor-1", "Analyst One",
                List.of("ANALYST", "REVIEWER"), "HUMAN", List.of("alert:read", "model:review"),
                AuditAction.SUBMIT_ANALYST_DECISION, AuditResourceType.ALERT, "alert-1",
                Instant.parse("2026-10-25T10:15:30.123456789Z"), "correlation-1", "request-1",
                "alert-service", AuditEventDocument.PARTITION_KEY, 7L, AuditOutcome.FAILED,
                AuditFailureCategory.DEPENDENCY, "timeout", metadata, "a".repeat(64), null,
                AuditEventDocument.HASH_ALGORITHM, "1.0"
        );
        return unhashed.withEventHash(AuditEventHasher.hash(unhashed));
    }

    private static AuditEventDocument changed(AuditEventDocument d, String field) {
        return new AuditEventDocument(
                field.equals("auditId") ? "audit-2" : d.auditId(),
                field.equals("eventType") ? AuditAction.UPDATE_FRAUD_CASE : d.eventType(),
                field.equals("actorId") ? "actor-2" : d.actorId(),
                field.equals("actorDisplayName") ? "Analyst Two" : d.actorDisplayName(),
                field.equals("actorRoles") ? List.of("AUDITOR") : d.actorRoles(),
                field.equals("actorType") ? "SERVICE" : d.actorType(),
                field.equals("actorAuthorities") ? List.of("audit:read") : d.actorAuthorities(),
                field.equals("action") ? AuditAction.UPDATE_FRAUD_CASE : d.action(),
                field.equals("resourceType") ? AuditResourceType.FRAUD_CASE : d.resourceType(),
                field.equals("resourceId") ? "case-2" : d.resourceId(),
                field.equals("createdAt") ? d.createdAt().plusMillis(1) : d.createdAt(),
                field.equals("correlationId") ? "correlation-2" : d.correlationId(),
                field.equals("requestId") ? "request-2" : d.requestId(),
                field.equals("sourceService") ? "other-service" : d.sourceService(),
                field.equals("partitionKey") ? "source_service:other" : d.partitionKey(),
                field.equals("chainPosition") ? 8L : d.chainPosition(),
                field.equals("outcome") ? AuditOutcome.REJECTED : d.outcome(),
                field.equals("failureCategory") ? AuditFailureCategory.AUTHORIZATION : d.failureCategory(),
                field.equals("failureReason") ? "forbidden" : d.failureReason(),
                field.equals("metadataSummary") ? changedMetadata(d.metadataSummary(), "reasonCode") : d.metadataSummary(),
                field.equals("previousEventHash") ? "b".repeat(64) : d.previousEventHash(),
                d.eventHash(),
                field.equals("hashAlgorithm") ? "SHA-512" : d.hashAlgorithm(),
                field.equals("schemaVersion") ? "2.0" : d.schemaVersion()
        );
    }

    private static AuditEventDocument copy(AuditEventDocument d, List<String> roles, List<String> authorities,
            Long chainPosition, AuditEventMetadataSummary metadata) {
        return new AuditEventDocument(d.auditId(), d.eventType(), d.actorId(), d.actorDisplayName(), roles,
                d.actorType(), authorities, d.action(), d.resourceType(), d.resourceId(), d.createdAt(),
                d.correlationId(), d.requestId(), d.sourceService(), d.partitionKey(), chainPosition, d.outcome(),
                d.failureCategory(), d.failureReason(), metadata, d.previousEventHash(), d.eventHash(),
                d.hashAlgorithm(), d.schemaVersion());
    }

    private static AuditEventMetadataSummary changedMetadata(AuditEventMetadataSummary m, String field) {
        return new AuditEventMetadataSummary(
                field.equals("correlationId") ? "other-correlation" : m.correlationId(),
                field.equals("requestId") ? "other-request" : m.requestId(),
                field.equals("sourceService") ? "other-service" : m.sourceService(),
                field.equals("schemaVersion") ? "2.0" : m.schemaVersion(),
                field.equals("failureCategory") ? "AUTHORIZATION" : m.failureCategory(),
                field.equals("failureReason") ? "forbidden" : m.failureReason(),
                field.equals("endpointAction") ? "GET /other" : m.endpointAction(),
                field.equals("filtersSummary") ? "status=closed" : m.filtersSummary(),
                field.equals("countReturned") ? 4 : m.countReturned(),
                field.equals("from") ? "2026-10-23T00:00:00Z" : m.from(),
                field.equals("to") ? "2026-10-26T00:00:00Z" : m.to(),
                field.equals("limit") ? 51 : m.limit(),
                field.equals("returnedCount") ? 4 : m.returnedCount(),
                field.equals("exportStatus") ? "PARTIAL" : m.exportStatus(),
                field.equals("reasonCode") ? "OTHER" : m.reasonCode(),
                field.equals("externalAnchorStatus") ? "MISSING" : m.externalAnchorStatus(),
                field.equals("anchorCoverage")
                        ? new AuditEventMetadataSummary.AnchorCoverageSummary(5, 4, 3, 2, 0.6d)
                        : m.anchorCoverage(),
                field.equals("exportFingerprint") ? "other-export" : m.exportFingerprint(),
                field.equals("trustLevel") ? "DEGRADED" : m.trustLevel(),
                field.equals("internalIntegrityStatus") ? "BROKEN" : m.internalIntegrityStatus(),
                field.equals("externalIntegrityStatus") ? "UNAVAILABLE" : m.externalIntegrityStatus(),
                field.equals("attestationFingerprint") ? "other-attestation" : m.attestationFingerprint()
        );
    }

    private static <T> Set<T> union(Set<T> left, Set<T> right) {
        var values = new java.util.HashSet<>(left);
        values.addAll(right);
        return values;
    }
}
