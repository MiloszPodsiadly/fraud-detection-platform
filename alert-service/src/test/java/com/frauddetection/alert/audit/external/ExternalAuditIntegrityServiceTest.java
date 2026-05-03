package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.AuditAnchorDocument;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.audit.AuditAnchorRepository;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("failure-injection")
@Tag("invariant-proof")
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
        assertThat(response.reasonCode()).isEqualTo("EXTERNAL_ANCHOR_ID_MISMATCH");
        assertThat(registry.get("fraud_platform_audit_external_tampering_detected_total")
                .tag("reason", "EXTERNAL_ANCHOR_ID_MISMATCH")
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
    void shouldExposeSignedMetadataOnlyAfterTrustAuthorityVerification() {
        AuditAnchorRepository repository = mock(AuditAnchorRepository.class);
        ExternalAuditAnchorSink signedSink = mock(ExternalAuditAnchorSink.class);
        ExternalAuditAnchorPublicationStatusRepository publicationRepository =
                mock(ExternalAuditAnchorPublicationStatusRepository.class);
        AuditTrustAuthorityClient trustAuthorityClient = mock(AuditTrustAuthorityClient.class);
        AuditAnchorDocument local = localAnchor("local-anchor-1", 2L, "hash-2");
        ExternalAuditAnchor external = external(local);
        ExternalAnchorReference reference = new ExternalAnchorReference(
                "local-anchor-1",
                "audit-anchors/source_service-alert-service/00000000000000000002.json",
                "hash-2",
                "hash-2",
                Instant.parse("2026-04-27T10:01:00Z")
        );
        when(repository.findLatestByPartitionKey("source_service:alert-service")).thenReturn(Optional.of(local));
        when(signedSink.findByChainPosition("source_service:alert-service", 2L)).thenReturn(Optional.of(external));
        when(signedSink.externalReference(external)).thenReturn(Optional.of(reference));
        when(signedSink.immutabilityLevel()).thenReturn(ExternalImmutabilityLevel.NONE);
        when(publicationRepository.findByLocalAnchorId("local-anchor-1"))
                .thenReturn(Optional.of(publicationStatus("SIGNED")));
        when(trustAuthorityClient.verify(any(AuditAnchorSigningPayload.class), any(SignedAuditAnchorPayload.class)))
                .thenReturn(AuditTrustSignatureVerificationResult.invalid("SIGNATURE_INVALID"))
                .thenReturn(AuditTrustSignatureVerificationResult.valid());
        ExternalAuditIntegrityService signedService = new ExternalAuditIntegrityService(
                repository,
                signedSink,
                new ExternalAuditIntegrityQueryParser(),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                mock(AuditService.class),
                publicationRepository,
                trustAuthorityClient
        );

        ExternalAuditIntegrityResponse rejected = signedService.verify("alert-service", 100);
        ExternalAuditIntegrityResponse accepted = signedService.verify("alert-service", 100);

        assertThat(rejected.status()).isEqualTo("INVALID");
        assertThat(rejected.signatureVerificationStatus()).isEqualTo("INVALID");
        assertThat(accepted.externalAnchor().externalReference().signatureStatus())
                .isEqualTo("SIGNED");
        assertThat(accepted.signatureVerificationStatus()).isEqualTo("VALID");
    }

    @Test
    void shouldExposeUnavailableUnknownKeyAndUnsignedSignatureVerificationStates() {
        AuditAnchorRepository repository = mock(AuditAnchorRepository.class);
        ExternalAuditAnchorSink signedSink = mock(ExternalAuditAnchorSink.class);
        ExternalAuditAnchorPublicationStatusRepository publicationRepository =
                mock(ExternalAuditAnchorPublicationStatusRepository.class);
        AuditTrustAuthorityClient trustAuthorityClient = mock(AuditTrustAuthorityClient.class);
        AuditAnchorDocument local = localAnchor("local-anchor-1", 2L, "hash-2");
        ExternalAuditAnchor external = external(local);
        ExternalAnchorReference reference = new ExternalAnchorReference(
                "local-anchor-1",
                "audit-anchors/source_service-alert-service/00000000000000000002.json",
                "hash-2",
                "hash-2",
                Instant.parse("2026-04-27T10:01:00Z")
        );
        when(repository.findLatestByPartitionKey("source_service:alert-service")).thenReturn(Optional.of(local));
        when(signedSink.findByChainPosition("source_service:alert-service", 2L)).thenReturn(Optional.of(external));
        when(signedSink.externalReference(external)).thenReturn(Optional.of(reference));
        when(signedSink.immutabilityLevel()).thenReturn(ExternalImmutabilityLevel.NONE);
        when(publicationRepository.findByLocalAnchorId("local-anchor-1"))
                .thenReturn(Optional.of(publicationStatus("SIGNED")))
                .thenReturn(Optional.of(publicationStatus("SIGNED")))
                .thenReturn(Optional.of(publicationStatus("UNSIGNED")));
        when(trustAuthorityClient.verify(any(AuditAnchorSigningPayload.class), any(SignedAuditAnchorPayload.class)))
                .thenReturn(AuditTrustSignatureVerificationResult.unavailable())
                .thenReturn(AuditTrustSignatureVerificationResult.unknownKey());
        ExternalAuditIntegrityService signedService = new ExternalAuditIntegrityService(
                repository,
                signedSink,
                new ExternalAuditIntegrityQueryParser(),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                mock(AuditService.class),
                publicationRepository,
                trustAuthorityClient
        );

        ExternalAuditIntegrityResponse unavailable = signedService.verify("alert-service", 100);
        ExternalAuditIntegrityResponse unknownKey = signedService.verify("alert-service", 100);
        ExternalAuditIntegrityResponse unsigned = signedService.verify("alert-service", 100);

        assertThat(unavailable.status()).isEqualTo("VALID");
        assertThat(unavailable.signatureVerificationStatus()).isEqualTo("UNAVAILABLE");
        assertThat(unknownKey.status()).isEqualTo("INVALID");
        assertThat(unknownKey.signatureVerificationStatus()).isEqualTo("UNKNOWN_KEY");
        assertThat(unsigned.status()).isEqualTo("VALID");
        assertThat(unsigned.signatureVerificationStatus()).isEqualTo("UNSIGNED");
    }

    @Test
    void shouldDowngradeUnsignedAndUnavailableSignaturesWhenTrustAuthorityEnabled() {
        AuditAnchorRepository repository = mock(AuditAnchorRepository.class);
        ExternalAuditAnchorSink signedSink = mock(ExternalAuditAnchorSink.class);
        ExternalAuditAnchorPublicationStatusRepository publicationRepository =
                mock(ExternalAuditAnchorPublicationStatusRepository.class);
        AuditAnchorDocument local = localAnchor("local-anchor-1", 2L, "hash-2");
        ExternalAuditAnchor external = external(local);
        ExternalAnchorReference reference = externalReference();
        when(repository.findLatestByPartitionKey("source_service:alert-service")).thenReturn(Optional.of(local));
        when(signedSink.findByChainPosition("source_service:alert-service", 2L)).thenReturn(Optional.of(external));
        when(signedSink.externalReference(external)).thenReturn(Optional.of(reference));
        when(signedSink.immutabilityLevel()).thenReturn(ExternalImmutabilityLevel.NONE);
        when(publicationRepository.findByLocalAnchorId("local-anchor-1"))
                .thenReturn(Optional.of(publicationStatus("UNSIGNED")))
                .thenThrow(new DataAccessResourceFailureException("mongo unavailable"));
        AuditTrustAuthorityProperties properties = new AuditTrustAuthorityProperties();
        properties.setEnabled(true);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExternalAuditIntegrityService signedService = new ExternalAuditIntegrityService(
                repository,
                signedSink,
                new ExternalAuditIntegrityQueryParser(),
                new AlertServiceMetrics(registry),
                mock(AuditService.class),
                publicationRepository,
                mock(AuditTrustAuthorityClient.class),
                properties
        );

        ExternalAuditIntegrityResponse unsigned = signedService.verify("alert-service", 100);
        ExternalAuditIntegrityResponse unavailable = signedService.verify("alert-service", 100);

        assertThat(unsigned.status()).isEqualTo("PARTIAL");
        assertThat(unsigned.reasonCode()).isEqualTo("SIGNATURE_UNSIGNED");
        assertThat(unsigned.status()).isNotEqualTo("VALID");
        assertThat(unavailable.status()).isEqualTo("PARTIAL");
        assertThat(unavailable.reasonCode()).isEqualTo("SIGNATURE_UNAVAILABLE");
        assertThat(unavailable.status()).isNotEqualTo("VALID");
        assertThat(registry.get("audit_signature_policy_result_total")
                .tag("result", "PARTIAL")
                .counter()
                .count()).isEqualTo(2.0d);
    }

    @Test
    void shouldInvalidateUnsignedAndUnavailableSignaturesWhenTrustAuthoritySigningRequired() {
        AuditAnchorRepository repository = mock(AuditAnchorRepository.class);
        ExternalAuditAnchorSink signedSink = mock(ExternalAuditAnchorSink.class);
        ExternalAuditAnchorPublicationStatusRepository publicationRepository =
                mock(ExternalAuditAnchorPublicationStatusRepository.class);
        AuditAnchorDocument local = localAnchor("local-anchor-1", 2L, "hash-2");
        ExternalAuditAnchor external = external(local);
        ExternalAnchorReference reference = externalReference();
        when(repository.findLatestByPartitionKey("source_service:alert-service")).thenReturn(Optional.of(local));
        when(signedSink.findByChainPosition("source_service:alert-service", 2L)).thenReturn(Optional.of(external));
        when(signedSink.externalReference(external)).thenReturn(Optional.of(reference));
        when(signedSink.immutabilityLevel()).thenReturn(ExternalImmutabilityLevel.NONE);
        when(publicationRepository.findByLocalAnchorId("local-anchor-1"))
                .thenReturn(Optional.of(publicationStatus("UNSIGNED")))
                .thenThrow(new DataAccessResourceFailureException("mongo unavailable"));
        AuditTrustAuthorityProperties properties = new AuditTrustAuthorityProperties();
        properties.setEnabled(true);
        properties.setSigningRequired(true);
        ExternalAuditIntegrityService signedService = new ExternalAuditIntegrityService(
                repository,
                signedSink,
                new ExternalAuditIntegrityQueryParser(),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                mock(AuditService.class),
                publicationRepository,
                mock(AuditTrustAuthorityClient.class),
                properties
        );

        ExternalAuditIntegrityResponse unsigned = signedService.verify("alert-service", 100);
        ExternalAuditIntegrityResponse unavailable = signedService.verify("alert-service", 100);

        assertThat(unsigned.status()).isEqualTo("INVALID");
        assertThat(unsigned.reasonCode()).isEqualTo("SIGNATURE_UNSIGNED_REQUIRED");
        assertThat(unavailable.status()).isEqualTo("INVALID");
        assertThat(unavailable.reasonCode()).isEqualTo("SIGNATURE_UNAVAILABLE_REQUIRED");
    }

    @Test
    void shouldNotReportValidForUnsignedEmptyChainWhenSigningRequired() {
        AuditTrustAuthorityProperties properties = new AuditTrustAuthorityProperties();
        properties.setEnabled(true);
        properties.setSigningRequired(true);
        when(anchorRepository.findLatestByPartitionKey("source_service:alert-service")).thenReturn(Optional.empty());
        ExternalAuditIntegrityService signedService = new ExternalAuditIntegrityService(
                anchorRepository,
                sink,
                new ExternalAuditIntegrityQueryParser(),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                mock(AuditService.class),
                null,
                mock(AuditTrustAuthorityClient.class),
                properties
        );

        ExternalAuditIntegrityResponse response = signedService.verify("alert-service", 100);

        assertThat(response.status()).isEqualTo("INVALID");
        assertThat(response.reasonCode()).isEqualTo("SIGNATURE_UNSIGNED_REQUIRED");
        assertThat(response.signatureVerificationStatus()).isEqualTo("UNSIGNED");
        assertThat(response.violations()).extracting("violationType")
                .containsExactly("SIGNATURE_UNSIGNED_REQUIRED");
    }

    @Test
    void shouldFailClosedForRegulatorSignatureScenariosWhenSigningRequired() {
        AuditAnchorRepository repository = mock(AuditAnchorRepository.class);
        ExternalAuditAnchorSink signedSink = mock(ExternalAuditAnchorSink.class);
        ExternalAuditAnchorPublicationStatusRepository publicationRepository =
                mock(ExternalAuditAnchorPublicationStatusRepository.class);
        AuditTrustAuthorityClient trustAuthorityClient = mock(AuditTrustAuthorityClient.class);
        AuditAnchorDocument local = localAnchor("local-anchor-1", 2L, "hash-2");
        ExternalAuditAnchor external = external(local);
        when(repository.findLatestByPartitionKey("source_service:alert-service")).thenReturn(Optional.of(local));
        when(signedSink.findByChainPosition("source_service:alert-service", 2L)).thenReturn(Optional.of(external));
        when(signedSink.externalReference(external)).thenReturn(Optional.of(externalReference()));
        when(signedSink.immutabilityLevel()).thenReturn(ExternalImmutabilityLevel.NONE);
        when(publicationRepository.findByLocalAnchorId("local-anchor-1"))
                .thenThrow(new DataAccessResourceFailureException("mongo unavailable"))
                .thenReturn(Optional.of(publicationStatus("UNSIGNED")))
                .thenReturn(Optional.of(publicationStatus("SIGNED")));
        when(trustAuthorityClient.verify(any(AuditAnchorSigningPayload.class), any(SignedAuditAnchorPayload.class)))
                .thenReturn(AuditTrustSignatureVerificationResult.invalid("SIGNATURE_INVALID"));
        AuditTrustAuthorityProperties properties = new AuditTrustAuthorityProperties();
        properties.setEnabled(true);
        properties.setSigningRequired(true);
        ExternalAuditIntegrityService signedService = new ExternalAuditIntegrityService(
                repository,
                signedSink,
                new ExternalAuditIntegrityQueryParser(),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                mock(AuditService.class),
                publicationRepository,
                trustAuthorityClient,
                properties
        );

        ExternalAuditIntegrityResponse unavailable = signedService.verify("alert-service", 100);
        ExternalAuditIntegrityResponse unsigned = signedService.verify("alert-service", 100);
        ExternalAuditIntegrityResponse invalid = signedService.verify("alert-service", 100);

        assertThat(unavailable.status()).isEqualTo("INVALID");
        assertThat(unavailable.reasonCode()).isEqualTo("SIGNATURE_UNAVAILABLE_REQUIRED");
        assertThat(unsigned.status()).isEqualTo("INVALID");
        assertThat(unsigned.reasonCode()).isEqualTo("SIGNATURE_UNSIGNED_REQUIRED");
        assertThat(invalid.status()).isEqualTo("INVALID");
        assertThat(invalid.reasonCode()).isEqualTo("SIGNATURE_INVALID");
        assertThat(List.of(unavailable.status(), unsigned.status(), invalid.status()))
                .doesNotContain("VALID");
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
        ObjectStoreExternalAuditAnchorSink objectStoreSink = objectStoreSink(client);
        for (long chainPosition = 1L; chainPosition <= 500L; chainPosition++) {
            objectStoreSink.publish(ExternalAuditAnchor.from(
                    localAnchor("local-anchor-" + chainPosition, chainPosition, "hash-" + chainPosition),
                    objectStoreSink.sinkType()
            ));
        }
        client.removeRaw("audit-bucket", "audit-anchors/c291cmNlX3NlcnZpY2U6YWxlcnQtc2VydmljZQ/head.json");
        client.paginationSupported = false;
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
    void shouldReturnBoundedCoverageWithVisibleMissingRanges() {
        AuditAnchorRepository repository = mock(AuditAnchorRepository.class);
        ObjectStoreExternalAuditAnchorSink objectStoreSink = objectStoreSink();
        ExternalAuditAnchorPublicationStatusRepository publicationRepository =
                mock(ExternalAuditAnchorPublicationStatusRepository.class);
        objectStoreSink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), objectStoreSink.sinkType()));
        objectStoreSink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-2", 2L, "hash-2"), objectStoreSink.sinkType()));
        objectStoreSink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-3", 3L, "hash-3"), objectStoreSink.sinkType()));
        objectStoreSink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-5", 5L, "hash-5"), objectStoreSink.sinkType()));
        List<AuditAnchorDocument> anchors = List.of(
                localAnchor("local-anchor-1", 1L, "hash-1"),
                localAnchor("local-anchor-2", 2L, "hash-2"),
                localAnchor("local-anchor-3", 3L, "hash-3"),
                localAnchor("local-anchor-4", 4L, "hash-4"),
                localAnchor("local-anchor-5", 5L, "hash-5")
        );
        when(repository.findLatestByPartitionKey("source_service:alert-service"))
                .thenReturn(Optional.of(localAnchor("local-anchor-5", 5L, "hash-5")));
        when(repository.findByPartitionKeyAndChainPositionBetween("source_service:alert-service", 1L, 5L, 5))
                .thenReturn(anchors);
        when(publicationRepository.findByLocalAnchorIds(List.of(
                "local-anchor-1",
                "local-anchor-2",
                "local-anchor-3",
                "local-anchor-4",
                "local-anchor-5"
        ))).thenReturn(List.of(
                publicationStatusFor("local-anchor-1", 1L, ExternalAuditAnchor.STATUS_PUBLISHED),
                publicationStatusFor("local-anchor-2", 2L, ExternalAuditAnchor.STATUS_PUBLISHED),
                publicationStatusFor("local-anchor-3", 3L, ExternalAuditAnchor.STATUS_PUBLISHED),
                publicationStatusFor("local-anchor-5", 5L, ExternalAuditAnchor.STATUS_PUBLISHED)
        ));

        ExternalAuditAnchorCoverageResponse response = objectStoreIntegrityService(repository, objectStoreSink, publicationRepository)
                .coverage("alert-service", 5);

        assertThat(response.status()).isEqualTo("AVAILABLE");
        assertThat(response.latestLocalPosition()).isEqualTo(5L);
        assertThat(response.latestExternalPosition()).isEqualTo(5L);
        assertThat(response.positionLag()).isZero();
        assertThat(response.truncated()).isFalse();
        assertThat(response.missingRanges())
                .extracting(ExternalAuditAnchorMissingRange::fromChainPosition, ExternalAuditAnchorMissingRange::toChainPosition)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(4L, 4L));
    }

    @Test
    void shouldBuildCoverageFromPublicationStatusWithoutPerPositionExternalReads() {
        AuditAnchorRepository repository = mock(AuditAnchorRepository.class);
        ExternalAuditAnchorSink objectStoreSink = mock(ExternalAuditAnchorSink.class);
        ExternalAuditAnchorPublicationStatusRepository publicationRepository =
                mock(ExternalAuditAnchorPublicationStatusRepository.class);
        AuditAnchorDocument latest = localAnchor("local-anchor-5", 5L, "hash-5");
        List<AuditAnchorDocument> anchors = List.of(
                localAnchor("local-anchor-1", 1L, "hash-1"),
                localAnchor("local-anchor-2", 2L, "hash-2"),
                localAnchor("local-anchor-3", 3L, "hash-3"),
                localAnchor("local-anchor-4", 4L, "hash-4"),
                latest
        );
        when(repository.findLatestByPartitionKey("source_service:alert-service")).thenReturn(Optional.of(latest));
        when(repository.findByPartitionKeyAndChainPositionBetween("source_service:alert-service", 1L, 5L, 5))
                .thenReturn(anchors);
        when(objectStoreSink.latest("source_service:alert-service")).thenReturn(Optional.of(external(latest)));
        when(publicationRepository.findByLocalAnchorIds(List.of(
                "local-anchor-1",
                "local-anchor-2",
                "local-anchor-3",
                "local-anchor-4",
                "local-anchor-5"
        ))).thenReturn(List.of(
                publicationStatus("UNSIGNED", ExternalAuditAnchor.STATUS_PUBLISHED, "RECORDED", false),
                publicationStatusFor("local-anchor-2", 2L, ExternalAuditAnchor.STATUS_PUBLISHED),
                publicationStatusFor("local-anchor-3", 3L, ExternalAuditAnchor.STATUS_PUBLISHED),
                publicationStatusFor("local-anchor-5", 5L, ExternalAuditAnchor.STATUS_PUBLISHED)
        ));

        ExternalAuditAnchorCoverageResponse response = objectStoreIntegrityService(repository, objectStoreSink, publicationRepository)
                .coverage("alert-service", 5, 1L);

        assertThat(response.missingRanges())
                .extracting(ExternalAuditAnchorMissingRange::fromChainPosition, ExternalAuditAnchorMissingRange::toChainPosition)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(4L, 4L));
        verify(objectStoreSink, never()).findByChainPosition(anyString(), anyLong());
    }

    @Test
    void shouldRejectCoverageLimitAboveOneHundred() {
        assertThatThrownBy(() -> service.coverage("alert-service", 101))
                .isInstanceOf(com.frauddetection.alert.audit.InvalidAuditEventQueryException.class);
    }

    @Test
    void shouldExposeLocalAheadAndRequiredPublicationFailuresInCoverage() {
        AuditAnchorRepository repository = mock(AuditAnchorRepository.class);
        ExternalAuditAnchorSink objectStoreSink = mock(ExternalAuditAnchorSink.class);
        ExternalAuditAnchorPublicationStatusRepository publicationRepository =
                mock(ExternalAuditAnchorPublicationStatusRepository.class);
        AuditAnchorDocument local = localAnchor("local-anchor-2", 2L, "hash-2");
        when(repository.findLatestByPartitionKey("source_service:alert-service")).thenReturn(Optional.of(local));
        when(repository.findHeadWindow("source_service:alert-service", 5)).thenReturn(List.of(local));
        when(objectStoreSink.latest("source_service:alert-service")).thenReturn(Optional.of(external(local)));
        when(objectStoreSink.findByChainPosition("source_service:alert-service", 2L)).thenReturn(Optional.of(external(local)));
        when(publicationRepository.findByLocalAnchorId("local-anchor-2"))
                .thenReturn(Optional.of(publicationStatus(
                        "UNSIGNED",
                        ExternalAuditAnchor.STATUS_LOCAL_ANCHOR_CREATED_EXTERNAL_REQUIRED_FAILED,
                        "RECORDED",
                        true
                )));

        ExternalAuditAnchorCoverageResponse response = objectStoreIntegrityService(repository, objectStoreSink, publicationRepository)
                .coverage("alert-service", 5);

        assertThat(response.status()).isEqualTo("AVAILABLE");
        assertThat(response.coverageStatus()).isEqualTo("DEGRADED");
        assertThat(response.localAheadOfExternal()).isTrue();
        assertThat(response.requiredPublicationFailures()).isEqualTo(1);
        assertThat(response.unrecoveredCount()).isEqualTo(1);
    }

    @Test
    void shouldImproveCoverageWhenLocalStatusWasRecovered() {
        AuditAnchorRepository repository = mock(AuditAnchorRepository.class);
        ExternalAuditAnchorSink objectStoreSink = mock(ExternalAuditAnchorSink.class);
        ExternalAuditAnchorPublicationStatusRepository publicationRepository =
                mock(ExternalAuditAnchorPublicationStatusRepository.class);
        AuditAnchorDocument local = localAnchor("local-anchor-2", 2L, "hash-2");
        when(repository.findLatestByPartitionKey("source_service:alert-service")).thenReturn(Optional.of(local));
        when(repository.findHeadWindow("source_service:alert-service", 5)).thenReturn(List.of(local));
        when(repository.findByPartitionKeyAndChainPositionBetween("source_service:alert-service", 1L, 2L, 2))
                .thenReturn(List.of(
                        localAnchor("local-anchor-1", 1L, "hash-1"),
                        local
                ));
        when(objectStoreSink.latest("source_service:alert-service")).thenReturn(Optional.of(external(local)));
        when(objectStoreSink.findByChainPosition("source_service:alert-service", 1L))
                .thenReturn(Optional.of(external(localAnchor("local-anchor-1", 1L, "hash-1"))));
        when(objectStoreSink.findByChainPosition("source_service:alert-service", 2L)).thenReturn(Optional.of(external(local)));
        when(publicationRepository.findByLocalAnchorIds(List.of("local-anchor-1", "local-anchor-2")))
                .thenReturn(List.of(
                        publicationStatusFor("local-anchor-1", 1L, ExternalAuditAnchor.STATUS_PUBLISHED),
                        publicationStatusFor("local-anchor-2", 2L, ExternalAuditAnchor.STATUS_PUBLISHED)
                ));
        when(publicationRepository.findByLocalAnchorId("local-anchor-2"))
                .thenReturn(Optional.of(publicationStatus("UNSIGNED", ExternalAuditAnchor.STATUS_PUBLISHED, "RECOVERED", false)));

        ExternalAuditAnchorCoverageResponse response = objectStoreIntegrityService(repository, objectStoreSink, publicationRepository)
                .coverage("alert-service", 5);

        assertThat(response.coverageStatus()).isEqualTo("HEALTHY");
        assertThat(response.localAheadOfExternal()).isFalse();
        assertThat(response.recoveredCount()).isEqualTo(1);
        assertThat(response.unrecoveredCount()).isZero();
    }

    @Test
    void shouldReturnUnavailableCoverageWhenExternalHeadCannotBeProven() {
        AuditAnchorRepository repository = mock(AuditAnchorRepository.class);
        ExternalAuditAnchorSink unavailableSink = mock(ExternalAuditAnchorSink.class);
        when(unavailableSink.latest("source_service:alert-service"))
                .thenThrow(new ExternalAuditAnchorSinkException("HEAD_SCAN_PAGINATION_UNSUPPORTED", "unsupported"));
        when(repository.findLatestByPartitionKey("source_service:alert-service"))
                .thenReturn(Optional.of(localAnchor("local-anchor-5", 5L, "hash-5")));
        ExternalAuditIntegrityService unavailableService = objectStoreIntegrityService(repository, unavailableSink);

        ExternalAuditAnchorCoverageResponse response = unavailableService.coverage("alert-service", 5);

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        assertThat(response.reasonCode()).isEqualTo("HEAD_SCAN_PAGINATION_UNSUPPORTED");
        assertThat(response.missingRanges()).isEmpty();
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

    private ExternalAnchorReference externalReference() {
        return new ExternalAnchorReference(
                "local-anchor-1",
                "audit-anchors/source_service-alert-service/00000000000000000002.json",
                "hash-2",
                "hash-2",
                Instant.parse("2026-04-27T10:01:00Z")
        );
    }

    private ExternalAuditAnchorPublicationStatusDocument publicationStatus(String signatureStatus) {
        return publicationStatus(signatureStatus, ExternalAuditAnchor.STATUS_PUBLISHED, "RECORDED", false);
    }

    private ExternalAuditAnchorPublicationStatusDocument publicationStatus(
            String signatureStatus,
            String externalPublicationStatus,
            String localTrackingStatus,
            boolean externalRequired
    ) {
        return new ExternalAuditAnchorPublicationStatusDocument(
                "local-anchor-1",
                "source_service:alert-service",
                2L,
                ExternalAuditAnchor.STATUS_PUBLISHED.equals(externalPublicationStatus),
                externalPublicationStatus,
                ExternalAuditAnchor.STATUS_PUBLISHED.equals(externalPublicationStatus)
                        ? ExternalAuditAnchor.STATUS_PUBLISHED
                        : ExternalAuditAnchor.STATUS_FAILED,
                null,
                localTrackingStatus,
                "CREATED",
                externalRequired,
                Instant.parse("2026-04-27T10:01:00Z"),
                "object-store",
                "audit-anchors/source_service-alert-service/00000000000000000002.json",
                "hash-2",
                "hash-2",
                Instant.parse("2026-04-27T10:01:00Z"),
                "NONE",
                null,
                signatureStatus,
                "signature",
                "local-ed25519-key-1",
                "Ed25519",
                Instant.parse("2026-04-27T10:01:01Z"),
                "local-trust-authority",
                "payload-hash",
                1,
                null,
                Instant.parse("2026-04-27T10:01:00Z")
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

    private ExternalAuditAnchorPublicationStatusDocument publicationStatusFor(
            String localAnchorId,
            long chainPosition,
            String externalPublicationStatus
    ) {
        return new ExternalAuditAnchorPublicationStatusDocument(
                localAnchorId,
                "source_service:alert-service",
                chainPosition,
                ExternalAuditAnchor.STATUS_PUBLISHED.equals(externalPublicationStatus),
                externalPublicationStatus,
                ExternalAuditAnchor.STATUS_PUBLISHED.equals(externalPublicationStatus)
                        ? ExternalAuditAnchor.STATUS_PUBLISHED
                        : ExternalAuditAnchor.STATUS_FAILED,
                null,
                "RECORDED",
                "CREATED",
                false,
                Instant.parse("2026-04-27T10:01:00Z"),
                "object-store",
                "audit-anchors/source_service-alert-service/00000000000000000002.json",
                "hash-" + chainPosition,
                "hash-" + chainPosition,
                Instant.parse("2026-04-27T10:01:00Z"),
                "NONE",
                null,
                "UNSIGNED",
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                null,
                Instant.parse("2026-04-27T10:01:00Z")
        );
    }

    private ExternalAuditIntegrityService objectStoreIntegrityService(
            AuditAnchorRepository repository,
            ExternalAuditAnchorSink objectStoreSink,
            ExternalAuditAnchorPublicationStatusRepository publicationRepository
    ) {
        return new ExternalAuditIntegrityService(
                repository,
                objectStoreSink,
                new ExternalAuditIntegrityQueryParser(),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                mock(AuditService.class),
                publicationRepository,
                new DisabledAuditTrustAuthorityClient()
        );
    }

    private ObjectStoreExternalAuditAnchorSink objectStoreSink() {
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
