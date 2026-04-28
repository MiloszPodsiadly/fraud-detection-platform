package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.AuditAnchorDocument;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.audit.AuditAnchorRepository;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalAuditIntegrityServiceTest {

    private final AuditAnchorRepository anchorRepository = mock(AuditAnchorRepository.class);
    private final ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
    private final AuditService auditService = mock(AuditService.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ExternalAuditIntegrityService service = new ExternalAuditIntegrityService(
            anchorRepository,
            sink,
            new ExternalAuditIntegrityQueryParser(),
            new AlertServiceMetrics(meterRegistry),
            auditService
    );

    @Test
    void shouldReturnValidWhenExternalAnchorMatchesLocalHead() {
        AuditAnchorDocument local = localAnchor("local-anchor-1", 2L, "hash-2");
        ExternalAuditAnchor external = external(local);
        when(anchorRepository.findLatestByPartitionKey("source_service:alert-service")).thenReturn(Optional.of(local));
        when(sink.latest("source_service:alert-service")).thenReturn(Optional.of(external));

        ExternalAuditIntegrityResponse response = service.verify("alert-service", 100);

        assertThat(response.status()).isEqualTo("VALID");
        assertThat(response.violations()).isEmpty();
    }

    @Test
    void shouldDetectMissingExternalAnchorAsPartial() {
        AuditAnchorDocument local = localAnchor("local-anchor-1", 2L, "hash-2");
        when(anchorRepository.findLatestByPartitionKey("source_service:alert-service")).thenReturn(Optional.of(local));
        when(sink.latest("source_service:alert-service")).thenReturn(Optional.empty());

        ExternalAuditIntegrityResponse response = service.verify("alert-service", 100);

        assertThat(response.status()).isEqualTo("PARTIAL");
        assertThat(response.reasonCode()).isEqualTo("EXTERNAL_ANCHOR_MISSING");
    }

    @Test
    void shouldDetectMissingObjectStoreAnchorAsPartial() {
        AuditAnchorRepository repository = mock(AuditAnchorRepository.class);
        ExternalAuditAnchorSink objectStoreSink = objectStoreSink();
        when(repository.findLatestByPartitionKey("source_service:alert-service"))
                .thenReturn(Optional.of(localAnchor("local-anchor-1", 2L, "hash-2")));

        ExternalAuditIntegrityResponse response = objectStoreIntegrityService(repository, objectStoreSink)
                .verify("alert-service", 100);

        assertThat(response.status()).isEqualTo("PARTIAL");
        assertThat(response.reasonCode()).isEqualTo("EXTERNAL_ANCHOR_MISSING");
    }

    @Test
    void shouldDetectObjectStoreAnchorMismatchAsInvalid() {
        AuditAnchorRepository repository = mock(AuditAnchorRepository.class);
        AuditAnchorDocument local = localAnchor("local-anchor-1", 2L, "hash-2");
        ExternalAuditAnchorSink objectStoreSink = objectStoreSink();
        objectStoreSink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 2L, "tampered-hash"), objectStoreSink.sinkType()));
        when(repository.findLatestByPartitionKey("source_service:alert-service")).thenReturn(Optional.of(local));

        ExternalAuditIntegrityResponse response = objectStoreIntegrityService(repository, objectStoreSink)
                .verify("alert-service", 100);

        assertThat(response.status()).isEqualTo("INVALID");
        assertThat(response.violations()).extracting("violationType").contains("EXTERNAL_HASH_MISMATCH");
    }

    @Test
    void shouldDetectObjectStorePayloadHashMismatchAsInvalid() {
        AuditAnchorRepository repository = mock(AuditAnchorRepository.class);
        AuditAnchorDocument local = localAnchor("local-anchor-1", 2L, "hash-2");
        ObjectStoreExternalAuditAnchorSinkTest.InMemoryObjectStoreAuditAnchorClient client =
                new ObjectStoreExternalAuditAnchorSinkTest.InMemoryObjectStoreAuditAnchorClient();
        ObjectStoreExternalAuditAnchorSink objectStoreSink = objectStoreSink(client);
        objectStoreSink.publish(ExternalAuditAnchor.from(local, objectStoreSink.sinkType()));
        String key = objectStoreSink.objectKey("source_service:alert-service", 2L);
        byte[] tampered = new String(client.getObject("audit-bucket", key).orElseThrow(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("\"event_hash\":\"hash-2\"", "\"event_hash\":\"hash-X\"")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        client.putRaw("audit-bucket", key, tampered);
        when(repository.findLatestByPartitionKey("source_service:alert-service")).thenReturn(Optional.of(local));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExternalAuditIntegrityResponse response = objectStoreIntegrityService(repository, objectStoreSink, registry)
                .verify("alert-service", 100);

        assertThat(response.status()).isEqualTo("INVALID");
        assertThat(response.reasonCode()).isEqualTo("EXTERNAL_PAYLOAD_HASH_MISMATCH");
        assertThat(registry.get("fraud_platform_audit_external_tampering_detected_total")
                .tag("reason", "EXTERNAL_PAYLOAD_HASH_MISMATCH")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void shouldDetectObjectStoreObjectKeyMismatchAsInvalid() {
        AuditAnchorRepository repository = mock(AuditAnchorRepository.class);
        AuditAnchorDocument local = localAnchor("local-anchor-2", 2L, "hash-2");
        ObjectStoreExternalAuditAnchorSinkTest.InMemoryObjectStoreAuditAnchorClient client =
                new ObjectStoreExternalAuditAnchorSinkTest.InMemoryObjectStoreAuditAnchorClient();
        ObjectStoreExternalAuditAnchorSink objectStoreSink = objectStoreSink(client);
        objectStoreSink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), objectStoreSink.sinkType()));
        String correctKey = objectStoreSink.objectKey("source_service:alert-service", 1L);
        String wrongKey = objectStoreSink.objectKey("source_service:alert-service", 2L);
        client.putRaw("audit-bucket", wrongKey, client.getObject("audit-bucket", correctKey).orElseThrow());
        when(repository.findLatestByPartitionKey("source_service:alert-service")).thenReturn(Optional.of(local));

        ExternalAuditIntegrityResponse response = objectStoreIntegrityService(repository, objectStoreSink)
                .verify("alert-service", 100);

        assertThat(response.status()).isEqualTo("INVALID");
        assertThat(response.reasonCode()).isEqualTo("EXTERNAL_OBJECT_KEY_MISMATCH");
    }

    @Test
    void shouldDetectStaleExternalAnchorAsPartial() {
        AuditAnchorDocument local = localAnchor("local-anchor-2", 2L, "hash-2");
        AuditAnchorDocument stale = localAnchor("local-anchor-1", 1L, "hash-1");
        when(anchorRepository.findLatestByPartitionKey("source_service:alert-service")).thenReturn(Optional.of(local));
        when(sink.latest("source_service:alert-service")).thenReturn(Optional.of(external(stale)));

        ExternalAuditIntegrityResponse response = service.verify("alert-service", 100);

        assertThat(response.status()).isEqualTo("PARTIAL");
        assertThat(response.violations()).extracting("violationType").contains("STALE_EXTERNAL_ANCHOR");
    }

    @Test
    void shouldDetectHashMismatchAsInvalid() {
        AuditAnchorDocument local = localAnchor("local-anchor-1", 2L, "hash-2");
        ExternalAuditAnchor external = new ExternalAuditAnchor(
                "external-1",
                local.anchorId(),
                local.partitionKey(),
                local.chainPosition(),
                "tampered-hash",
                local.hashAlgorithm(),
                "1.0",
                Instant.parse("2026-04-27T10:01:00Z"),
                "local-file",
                "PUBLISHED"
        );
        when(anchorRepository.findLatestByPartitionKey("source_service:alert-service")).thenReturn(Optional.of(local));
        when(sink.latest("source_service:alert-service")).thenReturn(Optional.of(external));

        ExternalAuditIntegrityResponse response = service.verify("alert-service", 100);

        assertThat(response.status()).isEqualTo("INVALID");
        assertThat(response.violations()).extracting("violationType").contains("EXTERNAL_HASH_MISMATCH");
    }

    @Test
    void shouldDetectLocalAnchorIdMismatchAsInvalid() {
        AuditAnchorDocument local = localAnchor("local-anchor-1", 2L, "hash-2");
        ExternalAuditAnchor external = new ExternalAuditAnchor(
                "external-1",
                "different-local-anchor",
                local.partitionKey(),
                local.chainPosition(),
                local.lastEventHash(),
                local.hashAlgorithm(),
                "1.0",
                Instant.parse("2026-04-27T10:01:00Z"),
                "local-file",
                "PUBLISHED"
        );
        when(anchorRepository.findLatestByPartitionKey("source_service:alert-service")).thenReturn(Optional.of(local));
        when(sink.latest("source_service:alert-service")).thenReturn(Optional.of(external));

        ExternalAuditIntegrityResponse response = service.verify("alert-service", 100);

        assertThat(response.status()).isEqualTo("INVALID");
        assertThat(response.violations()).extracting("violationType").contains("EXTERNAL_LOCAL_ANCHOR_ID_MISMATCH");
    }

    @Test
    void shouldDetectExternalAnchorAheadAsInvalid() {
        AuditAnchorDocument local = localAnchor("local-anchor-1", 2L, "hash-2");
        ExternalAuditAnchor external = new ExternalAuditAnchor(
                "external-1",
                "local-anchor-3",
                local.partitionKey(),
                3L,
                "hash-3",
                local.hashAlgorithm(),
                "1.0",
                Instant.parse("2026-04-27T10:01:00Z"),
                "local-file",
                "PUBLISHED"
        );
        when(anchorRepository.findLatestByPartitionKey("source_service:alert-service")).thenReturn(Optional.of(local));
        when(sink.latest("source_service:alert-service")).thenReturn(Optional.of(external));

        ExternalAuditIntegrityResponse response = service.verify("alert-service", 100);

        assertThat(response.status()).isEqualTo("INVALID");
        assertThat(response.violations()).extracting("violationType").contains("EXTERNAL_CHAIN_POSITION_AHEAD");
    }

    @Test
    void shouldDetectHashAlgorithmMismatchAsInvalid() {
        AuditAnchorDocument local = localAnchor("local-anchor-1", 2L, "hash-2");
        ExternalAuditAnchor external = new ExternalAuditAnchor(
                "external-1",
                local.anchorId(),
                local.partitionKey(),
                local.chainPosition(),
                local.lastEventHash(),
                "SHA-1",
                "1.0",
                Instant.parse("2026-04-27T10:01:00Z"),
                "local-file",
                "PUBLISHED"
        );
        when(anchorRepository.findLatestByPartitionKey("source_service:alert-service")).thenReturn(Optional.of(local));
        when(sink.latest("source_service:alert-service")).thenReturn(Optional.of(external));

        ExternalAuditIntegrityResponse response = service.verify("alert-service", 100);

        assertThat(response.status()).isEqualTo("INVALID");
        assertThat(response.violations()).extracting("violationType").contains("EXTERNAL_HASH_ALGORITHM_MISMATCH");
    }

    @Test
    void shouldDetectUnsupportedSchemaVersionAsInvalid() {
        AuditAnchorDocument local = localAnchor("local-anchor-1", 2L, "hash-2");
        ExternalAuditAnchor external = new ExternalAuditAnchor(
                "external-1",
                local.anchorId(),
                local.partitionKey(),
                local.chainPosition(),
                local.lastEventHash(),
                local.hashAlgorithm(),
                "2.0",
                Instant.parse("2026-04-27T10:01:00Z"),
                "local-file",
                "PUBLISHED"
        );
        when(anchorRepository.findLatestByPartitionKey("source_service:alert-service")).thenReturn(Optional.of(local));
        when(sink.latest("source_service:alert-service")).thenReturn(Optional.of(external));

        ExternalAuditIntegrityResponse response = service.verify("alert-service", 100);

        assertThat(response.status()).isEqualTo("INVALID");
        assertThat(response.violations()).extracting("violationType").contains("EXTERNAL_SCHEMA_VERSION_UNSUPPORTED");
    }

    @Test
    void shouldReturnUnavailableWhenExternalSinkFails() {
        AuditAnchorDocument local = localAnchor("local-anchor-1", 2L, "hash-2");
        when(anchorRepository.findLatestByPartitionKey("source_service:alert-service")).thenReturn(Optional.of(local));
        when(sink.latest("source_service:alert-service"))
                .thenThrow(new ExternalAuditAnchorSinkException("IO_ERROR", "path /internal/detail unavailable"));

        ExternalAuditIntegrityResponse response = service.verify("alert-service", 100);

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        assertThat(response.reasonCode()).isEqualTo("EXTERNAL_ANCHOR_STORE_UNAVAILABLE");
        assertThat(response.message()).doesNotContain("/internal/detail");
    }

    @Test
    void shouldReturnUnavailableWhenObjectStoreHeadCannotBeProvenByPagination() {
        AuditAnchorRepository repository = mock(AuditAnchorRepository.class);
        ObjectStoreExternalAuditAnchorSinkTest.InMemoryObjectStoreAuditAnchorClient client =
                new ObjectStoreExternalAuditAnchorSinkTest.InMemoryObjectStoreAuditAnchorClient();
        client.paginationSupported = false;
        ObjectStoreExternalAuditAnchorSink objectStoreSink = objectStoreSink(client);
        for (long chainPosition = 1L; chainPosition <= 500L; chainPosition++) {
            objectStoreSink.publish(ExternalAuditAnchor.from(
                    localAnchor("local-anchor-" + chainPosition, chainPosition, "hash-" + chainPosition),
                    objectStoreSink.sinkType()
            ));
        }
        when(repository.findLatestByPartitionKey("source_service:alert-service"))
                .thenReturn(Optional.of(localAnchor("local-anchor-501", 501L, "hash-501")));

        ExternalAuditIntegrityResponse response = objectStoreIntegrityService(repository, objectStoreSink)
                .verify("alert-service", 100);

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        assertThat(response.status()).isNotEqualTo("VALID");
        assertThat(response.reasonCode()).isEqualTo("HEAD_SCAN_PAGINATION_UNSUPPORTED");
        assertThat(response.message()).doesNotContain("audit-bucket", "audit-anchors");
    }

    @Test
    void shouldReturnUnavailableWhenLocalAnchorStoreFails() {
        when(anchorRepository.findLatestByPartitionKey("source_service:alert-service"))
                .thenThrow(new DataAccessResourceFailureException("mongo internal timeout"));

        ExternalAuditIntegrityResponse response = service.verify("alert-service", 100);

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        assertThat(response.reasonCode()).isEqualTo("AUDIT_STORE_UNAVAILABLE");
        assertThat(response.message()).doesNotContain("mongo", "internal");
    }

    @Test
    void shouldReturnValidWithZeroCheckedWhenNoLocalAnchorExists() {
        when(anchorRepository.findLatestByPartitionKey("source_service:alert-service")).thenReturn(Optional.empty());

        ExternalAuditIntegrityResponse response = service.verify("alert-service", 100);

        assertThat(response.status()).isEqualTo("VALID");
        assertThat(response.checked()).isZero();
        assertThat(response.violations()).isEmpty();
    }

    @Test
    void shouldRejectExternalIntegrityLimitAboveMaximum() {
        assertThatThrownBy(() -> service.verify("alert-service", 501))
                .isInstanceOf(com.frauddetection.alert.audit.InvalidAuditEventQueryException.class);
    }

    @Test
    void shouldRecordExternalIntegrityMetricWithBoundedStatus() {
        when(anchorRepository.findLatestByPartitionKey("source_service:alert-service")).thenReturn(Optional.empty());

        service.verify("alert-service", 100);

        assertThat(meterRegistry.get("fraud_platform_audit_external_integrity_checks_total")
                .tag("status", "VALID")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    private ExternalAuditAnchor external(AuditAnchorDocument local) {
        return new ExternalAuditAnchor(
                "external-1",
                local.anchorId(),
                local.partitionKey(),
                local.chainPosition(),
                local.lastEventHash(),
                local.hashAlgorithm(),
                "1.0",
                Instant.parse("2026-04-27T10:01:00Z"),
                "local-file",
                "PUBLISHED"
        );
    }

    private ExternalAuditIntegrityService objectStoreIntegrityService(
            AuditAnchorRepository repository,
            ExternalAuditAnchorSink objectStoreSink
    ) {
        return objectStoreIntegrityService(repository, objectStoreSink, new SimpleMeterRegistry());
    }

    private ExternalAuditIntegrityService objectStoreIntegrityService(
            AuditAnchorRepository repository,
            ExternalAuditAnchorSink objectStoreSink,
            SimpleMeterRegistry registry
    ) {
        return new ExternalAuditIntegrityService(
                repository,
                objectStoreSink,
                new ExternalAuditIntegrityQueryParser(),
                new AlertServiceMetrics(registry),
                mock(AuditService.class)
        );
    }

    private ExternalAuditAnchorSink objectStoreSink() {
        return objectStoreSink(new ObjectStoreExternalAuditAnchorSinkTest.InMemoryObjectStoreAuditAnchorClient());
    }

    private ObjectStoreExternalAuditAnchorSink objectStoreSink(
            ObjectStoreExternalAuditAnchorSinkTest.InMemoryObjectStoreAuditAnchorClient client
    ) {
        return new ObjectStoreExternalAuditAnchorSink(
                "audit-bucket",
                "audit-anchors",
                client,
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
        );
    }

    private AuditAnchorDocument localAnchor(String anchorId, long chainPosition, String hash) {
        return new AuditAnchorDocument(
                anchorId,
                Instant.parse("2026-04-27T10:00:00Z"),
                "source_service:alert-service",
                hash,
                chainPosition,
                "SHA-256"
        );
    }
}
