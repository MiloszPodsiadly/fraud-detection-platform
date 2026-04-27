package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.AuditAnchorDocument;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.audit.AuditAnchorRepository;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalAuditIntegrityServiceTest {

    private final AuditAnchorRepository anchorRepository = mock(AuditAnchorRepository.class);
    private final ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
    private final AuditService auditService = mock(AuditService.class);
    private final ExternalAuditIntegrityService service = new ExternalAuditIntegrityService(
            anchorRepository,
            sink,
            new ExternalAuditIntegrityQueryParser(),
            new AlertServiceMetrics(new SimpleMeterRegistry()),
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
