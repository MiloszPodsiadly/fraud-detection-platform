package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.AuditAnchorDocument;
import com.frauddetection.alert.audit.AuditAnchorRepository;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalAuditAnchorPublisherTest {

    private final AuditAnchorRepository anchorRepository = mock(AuditAnchorRepository.class);
    private final ExternalAuditAnchorPublicationStatusRepository publicationStatusRepository =
            mock(ExternalAuditAnchorPublicationStatusRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AlertServiceMetrics metrics = new AlertServiceMetrics(meterRegistry);

    @Test
    void shouldRecordSinkFailureWithoutThrowing() {
        ExternalAuditAnchorSink sink = new DisabledExternalAuditAnchorSink();
        when(anchorRepository.findByPartitionKeyAndChainPositionGreaterThan("source_service:alert-service", 0L, 100))
                .thenReturn(List.of(localAnchor("local-anchor-1", 1L, "hash-1")));
        ExternalAuditAnchorPublisher publisher = new ExternalAuditAnchorPublisher(
                anchorRepository,
                publicationStatusRepository,
                sink,
                metrics,
                Clock.fixed(Instant.parse("2026-04-27T10:01:00Z"), ZoneOffset.UTC),
                100
        );

        ExternalAuditAnchorPublishResult result = publisher.publishDefaultWindow();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.partial()).isZero();
        assertThat(meterRegistry.get("fraud_platform_audit_external_anchor_publish_failed_total")
                .tag("sink", "disabled")
                .tag("reason", "DISABLED")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void shouldPublishOnlyAnchorsAfterLatestExternalPosition() {
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AuditAnchorDocument alreadyPublished = localAnchor("local-anchor-1", 1L, "hash-1");
        AuditAnchorDocument next = localAnchor("local-anchor-2", 2L, "hash-2");
        when(sink.sinkType()).thenReturn("local-file");
        when(sink.latest("source_service:alert-service")).thenReturn(java.util.Optional.of(ExternalAuditAnchor.from(alreadyPublished, "local-file")));
        when(anchorRepository.findByPartitionKeyAndChainPositionGreaterThan("source_service:alert-service", 1L, 100))
                .thenReturn(List.of(next));
        when(sink.publish(org.mockito.ArgumentMatchers.any(ExternalAuditAnchor.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ExternalAuditAnchorPublisher publisher = new ExternalAuditAnchorPublisher(
                anchorRepository,
                publicationStatusRepository,
                sink,
                metrics,
                Clock.fixed(Instant.parse("2026-04-27T10:01:00Z"), ZoneOffset.UTC),
                100
        );

        ExternalAuditAnchorPublishResult result = publisher.publishDefaultWindow();

        assertThat(result.published()).isEqualTo(1);
        assertThat(result.partial()).isZero();
        verify(anchorRepository)
                .findByPartitionKeyAndChainPositionGreaterThan("source_service:alert-service", 1L, 100);
        verify(publicationStatusRepository)
                .recordSuccess(
                        org.mockito.ArgumentMatchers.eq(next),
                        org.mockito.ArgumentMatchers.eq(Instant.parse("2026-04-27T10:01:00Z")),
                        org.mockito.ArgumentMatchers.eq("local-file"),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.eq(ExternalImmutabilityLevel.NONE),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.eq("RECORDED"),
                        org.mockito.ArgumentMatchers.any(SignedAuditAnchorPayload.class)
                );
    }

    @Test
    void shouldContinueWithNextEligibleAnchorAfterSinglePublishFailure() {
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AuditAnchorDocument first = localAnchor("local-anchor-1", 1L, "hash-1");
        AuditAnchorDocument second = localAnchor("local-anchor-2", 2L, "hash-2");
        when(sink.sinkType()).thenReturn("local-file");
        when(sink.latest("source_service:alert-service")).thenReturn(java.util.Optional.empty());
        when(anchorRepository.findByPartitionKeyAndChainPositionGreaterThan("source_service:alert-service", 0L, 100))
                .thenReturn(List.of(first, second));
        when(sink.publish(any(ExternalAuditAnchor.class)))
                .thenThrow(new ExternalAuditAnchorSinkException("CONFLICT", "conflict detail"))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ExternalAuditAnchorPublisher publisher = new ExternalAuditAnchorPublisher(
                anchorRepository,
                publicationStatusRepository,
                sink,
                metrics,
                Clock.fixed(Instant.parse("2026-04-27T10:01:00Z"), ZoneOffset.UTC),
                100
        );

        ExternalAuditAnchorPublishResult result = publisher.publishDefaultWindow();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.published()).isEqualTo(1);
        assertThat(result.partial()).isZero();
        verify(publicationStatusRepository).recordFailure(first, Instant.parse("2026-04-27T10:01:00Z"), "CONFLICT");
        verify(publicationStatusRepository).recordSuccess(
                org.mockito.ArgumentMatchers.eq(second),
                org.mockito.ArgumentMatchers.eq(Instant.parse("2026-04-27T10:01:00Z")),
                org.mockito.ArgumentMatchers.eq("local-file"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(ExternalImmutabilityLevel.NONE),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("RECORDED"),
                org.mockito.ArgumentMatchers.any(SignedAuditAnchorPayload.class)
        );
        assertThat(meterRegistry.get("fraud_platform_audit_external_anchor_publish_failed_total")
                .tag("sink", "local-file")
                .tag("reason", "CONFLICT")
                .counter()
                .count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("fraud_platform_audit_external_anchor_published_total")
                .tag("sink", "local-file")
                .tag("status", "FAILED")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void shouldRecordPartialPublicationSeparatelyFromSuccess() {
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AuditAnchorDocument anchor = localAnchor("local-anchor-1", 1L, "hash-1");
        when(sink.sinkType()).thenReturn("object-store");
        when(sink.latest("source_service:alert-service")).thenReturn(java.util.Optional.empty());
        when(sink.immutabilityLevel()).thenReturn(ExternalImmutabilityLevel.CONFIGURED);
        when(anchorRepository.findByPartitionKeyAndChainPositionGreaterThan("source_service:alert-service", 0L, 100))
                .thenReturn(List.of(anchor));
        when(sink.publish(any(ExternalAuditAnchor.class)))
                .thenAnswer(invocation -> ((ExternalAuditAnchor) invocation.getArgument(0)).partial());
        when(sink.externalReference(any(ExternalAuditAnchor.class)))
                .thenReturn(java.util.Optional.of(new ExternalAnchorReference(
                        "local-anchor-1",
                        "audit-anchors/partition/00000000000000000001.json",
                        "hash-1",
                        "hash-1",
                        Instant.parse("2026-04-27T10:01:00Z")
                )));
        ExternalAuditAnchorPublisher publisher = new ExternalAuditAnchorPublisher(
                anchorRepository,
                publicationStatusRepository,
                sink,
                metrics,
                Clock.fixed(Instant.parse("2026-04-27T10:01:00Z"), ZoneOffset.UTC),
                100
        );

        ExternalAuditAnchorPublishResult result = publisher.publishDefaultWindow();

        assertThat(result.published()).isZero();
        assertThat(result.partial()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        verify(publicationStatusRepository, never()).recordSuccess(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(publicationStatusRepository).recordPartial(
                org.mockito.ArgumentMatchers.eq(anchor),
                org.mockito.ArgumentMatchers.eq(Instant.parse("2026-04-27T10:01:00Z")),
                org.mockito.ArgumentMatchers.eq("object-store"),
                org.mockito.ArgumentMatchers.any(ExternalAnchorReference.class),
                org.mockito.ArgumentMatchers.eq(ExternalImmutabilityLevel.CONFIGURED),
                org.mockito.ArgumentMatchers.eq(ExternalAuditAnchor.REASON_HEAD_MANIFEST_UPDATE_FAILED),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any(SignedAuditAnchorPayload.class)
        );
        assertThat(meterRegistry.get("fraud_platform_audit_external_anchor_published_total")
                .tag("sink", "object-store")
                .tag("status", "UNVERIFIED")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void shouldRecordPartialWhenSigningIsRequiredAndTrustAuthorityIsUnavailable() {
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AuditAnchorDocument anchor = localAnchor("local-anchor-1", 1L, "hash-1");
        AuditTrustAuthorityProperties properties = new AuditTrustAuthorityProperties();
        properties.setEnabled(true);
        properties.setSigningRequired(true);
        when(sink.sinkType()).thenReturn("object-store");
        when(sink.latest("source_service:alert-service")).thenReturn(java.util.Optional.empty());
        when(sink.immutabilityLevel()).thenReturn(ExternalImmutabilityLevel.CONFIGURED);
        when(anchorRepository.findByPartitionKeyAndChainPositionGreaterThan("source_service:alert-service", 0L, 100))
                .thenReturn(List.of(anchor));
        when(sink.publish(any(ExternalAuditAnchor.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(sink.externalReference(any(ExternalAuditAnchor.class)))
                .thenReturn(java.util.Optional.of(new ExternalAnchorReference(
                        "local-anchor-1",
                        "audit-anchors/partition/00000000000000000001.json",
                        "hash-1",
                        "hash-1",
                        Instant.parse("2026-04-27T10:01:00Z")
                )));
        ExternalAuditAnchorPublisher publisher = new ExternalAuditAnchorPublisher(
                anchorRepository,
                publicationStatusRepository,
                sink,
                new UnavailableTrustAuthorityClient(),
                properties,
                metrics,
                Clock.fixed(Instant.parse("2026-04-27T10:01:00Z"), ZoneOffset.UTC),
                100
        );

        ExternalAuditAnchorPublishResult result = publisher.publishDefaultWindow();

        assertThat(result.published()).isZero();
        assertThat(result.partial()).isEqualTo(1);
        verify(publicationStatusRepository, never()).recordSuccess(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(publicationStatusRepository).recordPartial(
                org.mockito.ArgumentMatchers.eq(anchor),
                org.mockito.ArgumentMatchers.eq(Instant.parse("2026-04-27T10:01:00Z")),
                org.mockito.ArgumentMatchers.eq("object-store"),
                org.mockito.ArgumentMatchers.any(ExternalAnchorReference.class),
                org.mockito.ArgumentMatchers.eq(ExternalImmutabilityLevel.CONFIGURED),
                org.mockito.ArgumentMatchers.eq("SIGNATURE_FAILED"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.argThat(signature -> "SIGNATURE_FAILED".equals(signature.signatureStatus()))
        );
    }

    @Test
    void shouldNotReportCleanPublishedWhenStatusPersistenceFailsAfterExternalPublish() {
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AuditAnchorDocument anchor = localAnchor("local-anchor-1", 1L, "hash-1");
        when(sink.sinkType()).thenReturn("object-store");
        when(sink.latest("source_service:alert-service")).thenReturn(java.util.Optional.empty());
        when(sink.immutabilityLevel()).thenReturn(ExternalImmutabilityLevel.ENFORCED);
        when(anchorRepository.findByPartitionKeyAndChainPositionGreaterThan("source_service:alert-service", 0L, 100))
                .thenReturn(List.of(anchor));
        when(sink.publish(any(ExternalAuditAnchor.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(sink.externalReference(any(ExternalAuditAnchor.class)))
                .thenReturn(java.util.Optional.of(new ExternalAnchorReference(
                        "local-anchor-1",
                        "audit-anchors/partition/00000000000000000001.json",
                        "hash-1",
                        "hash-1",
                        Instant.parse("2026-04-27T10:01:00Z")
                )));
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("mongo down"))
                .when(publicationStatusRepository)
                .recordSuccess(
                        org.mockito.ArgumentMatchers.eq(anchor),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("object-store"),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(ExternalImmutabilityLevel.ENFORCED),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("RECORDED"),
                        org.mockito.ArgumentMatchers.any()
                );
        ExternalAuditAnchorPublisher publisher = new ExternalAuditAnchorPublisher(
                anchorRepository,
                publicationStatusRepository,
                sink,
                metrics,
                Clock.fixed(Instant.parse("2026-04-27T10:01:00Z"), ZoneOffset.UTC),
                100
        );

        ExternalAuditAnchorPublishResult result = publisher.publishDefaultWindow();

        assertThat(result.published()).isZero();
        assertThat(result.localStatusUnverified()).isEqualTo(1);
        assertThat(result.partial()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(meterRegistry.get("external_anchor_status_persistence_failed_total")
                .tag("sink", "object-store")
                .counter()
                .count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("fraud_platform_audit_external_anchor_published_total")
                .tag("sink", "object-store")
                .tag("status", ExternalAuditAnchor.STATUS_LOCAL_STATUS_UNVERIFIED)
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void shouldFailClosedWhenRequiredPublicationStatusCannotBePersisted() {
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AuditAnchorDocument anchor = localAnchor("local-anchor-1", 1L, "hash-1");
        when(sink.sinkType()).thenReturn("object-store");
        when(sink.immutabilityLevel()).thenReturn(ExternalImmutabilityLevel.ENFORCED);
        when(sink.publish(any(ExternalAuditAnchor.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(sink.externalReference(any(ExternalAuditAnchor.class)))
                .thenReturn(java.util.Optional.of(new ExternalAnchorReference(
                        "local-anchor-1",
                        "audit-anchors/partition/00000000000000000001.json",
                        "hash-1",
                        "hash-1",
                        Instant.parse("2026-04-27T10:01:00Z")
                )));
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("mongo down"))
                .when(publicationStatusRepository)
                .recordSuccess(
                        org.mockito.ArgumentMatchers.eq(anchor),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("object-store"),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(ExternalImmutabilityLevel.ENFORCED),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("RECORDED"),
                        org.mockito.ArgumentMatchers.any()
                );
        ExternalAuditAnchorPublisher publisher = new ExternalAuditAnchorPublisher(
                anchorRepository,
                publicationStatusRepository,
                sink,
                metrics,
                Clock.fixed(Instant.parse("2026-04-27T10:01:00Z"), ZoneOffset.UTC),
                100
        );

        assertThatThrownBy(() -> publisher.publishRequired(anchor))
                .isInstanceOf(ExternalAuditAnchorPublicationRequiredException.class)
                .extracting("reason")
                .isEqualTo(ExternalAuditAnchor.REASON_STATUS_PERSISTENCE_FAILED_AFTER_EXTERNAL_PUBLISH);
        assertThat(meterRegistry.get("external_anchor_status_persistence_failed_total")
                .tag("sink", "object-store")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void shouldFailClosedWhenRequiredPublicationCannotVerifyExternalWrite() {
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AuditAnchorDocument anchor = localAnchor("local-anchor-1", 1L, "hash-1");
        when(sink.sinkType()).thenReturn("object-store");
        when(sink.publish(any(ExternalAuditAnchor.class)))
                .thenAnswer(invocation -> ((ExternalAuditAnchor) invocation.getArgument(0)).unverified("WRITE_NOT_VERIFIED"));
        ExternalAuditAnchorPublisher publisher = new ExternalAuditAnchorPublisher(
                anchorRepository,
                publicationStatusRepository,
                sink,
                metrics,
                Clock.fixed(Instant.parse("2026-04-27T10:01:00Z"), ZoneOffset.UTC),
                100
        );

        assertThatThrownBy(() -> publisher.publishRequired(anchor))
                .isInstanceOf(ExternalAuditAnchorPublicationRequiredException.class)
                .extracting("reason")
                .isEqualTo("WRITE_NOT_VERIFIED");
        verify(publicationStatusRepository).recordRequiredFailure(
                anchor,
                Instant.parse("2026-04-27T10:01:00Z"),
                "WRITE_NOT_VERIFIED"
        );
        assertThat(meterRegistry.get("external_anchor_required_failed_after_local_anchor_total")
                .tag("sink", "object-store")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void shouldRecoverMissingLocalStatusWhenExternalObjectExistsAtSamePosition() {
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AuditAnchorDocument anchor = localAnchor("local-anchor-1", 1L, "hash-1");
        ExternalAuditAnchor external = ExternalAuditAnchor.from(anchor, "object-store");
        when(sink.sinkType()).thenReturn("object-store");
        when(sink.immutabilityLevel()).thenReturn(ExternalImmutabilityLevel.ENFORCED);
        when(anchorRepository.findHeadWindow("source_service:alert-service", 100)).thenReturn(List.of(anchor));
        when(publicationStatusRepository.findByLocalAnchorId("local-anchor-1")).thenReturn(java.util.Optional.empty());
        when(sink.findByChainPosition("source_service:alert-service", 1L)).thenReturn(java.util.Optional.of(external));
        when(sink.externalReference(external)).thenReturn(java.util.Optional.of(new ExternalAnchorReference(
                "local-anchor-1",
                "audit-anchors/partition/00000000000000000001.json",
                "hash-1",
                "hash-1",
                Instant.parse("2026-04-27T10:01:00Z")
        )));
        ExternalAuditAnchorPublisher publisher = new ExternalAuditAnchorPublisher(
                anchorRepository,
                publicationStatusRepository,
                sink,
                metrics,
                Clock.fixed(Instant.parse("2026-04-27T10:01:00Z"), ZoneOffset.UTC),
                100
        );

        ExternalAuditAnchorPublishResult result = publisher.reconcileAnchors(100);

        assertThat(result.recovered()).isEqualTo(1);
        assertThat(result.missing()).isZero();
        verify(publicationStatusRepository).recordRecovered(
                org.mockito.ArgumentMatchers.eq(anchor),
                org.mockito.ArgumentMatchers.eq(Instant.parse("2026-04-27T10:01:00Z")),
                org.mockito.ArgumentMatchers.eq("object-store"),
                org.mockito.ArgumentMatchers.any(ExternalAnchorReference.class),
                org.mockito.ArgumentMatchers.eq(ExternalImmutabilityLevel.ENFORCED),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any(SignedAuditAnchorPayload.class)
        );
        assertThat(meterRegistry.get("external_anchor_status_recovered_total")
                .tag("sink", "object-store")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void shouldReportMissingWhenReconciliationCannotFindExactExternalObject() {
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AuditAnchorDocument anchor = localAnchor("local-anchor-1", 1L, "hash-1");
        when(sink.sinkType()).thenReturn("object-store");
        when(anchorRepository.findHeadWindow("source_service:alert-service", 100)).thenReturn(List.of(anchor));
        when(publicationStatusRepository.findByLocalAnchorId("local-anchor-1"))
                .thenReturn(java.util.Optional.of(requiredFailedStatus(anchor)));
        when(sink.findByChainPosition("source_service:alert-service", 1L)).thenReturn(java.util.Optional.empty());
        ExternalAuditAnchorPublisher publisher = new ExternalAuditAnchorPublisher(
                anchorRepository,
                publicationStatusRepository,
                sink,
                metrics,
                Clock.fixed(Instant.parse("2026-04-27T10:01:00Z"), ZoneOffset.UTC),
                100
        );

        ExternalAuditAnchorPublishResult result = publisher.reconcileAnchors(100);

        assertThat(result.missing()).isEqualTo(1);
        assertThat(result.recovered()).isZero();
        verify(publicationStatusRepository).recordRecoveryMissing(anchor, Instant.parse("2026-04-27T10:01:00Z"));
    }

    @Test
    void shouldReportInvalidWhenReconciliationFindsMismatchedExternalObject() {
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AuditAnchorDocument anchor = localAnchor("local-anchor-1", 1L, "hash-1");
        AuditAnchorDocument mismatched = localAnchor("local-anchor-1", 1L, "tampered-hash");
        when(sink.sinkType()).thenReturn("object-store");
        when(anchorRepository.findHeadWindow("source_service:alert-service", 100)).thenReturn(List.of(anchor));
        when(publicationStatusRepository.findByLocalAnchorId("local-anchor-1")).thenReturn(java.util.Optional.empty());
        when(sink.findByChainPosition("source_service:alert-service", 1L))
                .thenReturn(java.util.Optional.of(ExternalAuditAnchor.from(mismatched, "object-store")));
        ExternalAuditAnchorPublisher publisher = new ExternalAuditAnchorPublisher(
                anchorRepository,
                publicationStatusRepository,
                sink,
                metrics,
                Clock.fixed(Instant.parse("2026-04-27T10:01:00Z"), ZoneOffset.UTC),
                100
        );

        ExternalAuditAnchorPublishResult result = publisher.reconcileAnchors(100);

        assertThat(result.invalid()).isEqualTo(1);
        assertThat(result.recovered()).isZero();
        verify(publicationStatusRepository).recordRecoveryInvalid(
                org.mockito.ArgumentMatchers.eq(anchor),
                org.mockito.ArgumentMatchers.eq(Instant.parse("2026-04-27T10:01:00Z")),
                org.mockito.ArgumentMatchers.eq("EXTERNAL_PAYLOAD_HASH_MISMATCH")
        );
    }

    @Test
    void shouldCapPublishLimitAtFiveHundred() {
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        when(sink.sinkType()).thenReturn("local-file");
        when(sink.latest("source_service:alert-service")).thenReturn(java.util.Optional.empty());
        when(anchorRepository.findByPartitionKeyAndChainPositionGreaterThan("source_service:alert-service", 0L, 500))
                .thenReturn(List.of());
        ExternalAuditAnchorPublisher publisher = new ExternalAuditAnchorPublisher(
                anchorRepository,
                publicationStatusRepository,
                sink,
                metrics,
                Clock.fixed(Instant.parse("2026-04-27T10:01:00Z"), ZoneOffset.UTC),
                999
        );

        ExternalAuditAnchorPublishResult result = publisher.publishDefaultWindow();

        assertThat(result.limit()).isEqualTo(500);
        verify(anchorRepository).findByPartitionKeyAndChainPositionGreaterThan("source_service:alert-service", 0L, 500);
    }

    @Test
    void shouldReturnFailedResultWhenLatestExternalAnchorLookupFails() {
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        when(sink.sinkType()).thenReturn("local-file");
        when(sink.latest("source_service:alert-service"))
                .thenThrow(new ExternalAuditAnchorSinkException("IO_ERROR", "file path detail"));
        ExternalAuditAnchorPublisher publisher = new ExternalAuditAnchorPublisher(
                anchorRepository,
                publicationStatusRepository,
                sink,
                metrics,
                Clock.fixed(Instant.parse("2026-04-27T10:01:00Z"), ZoneOffset.UTC),
                100
        );

        ExternalAuditAnchorPublishResult result = publisher.publishDefaultWindow();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(meterRegistry.get("fraud_platform_audit_external_anchor_publish_failed_total")
                .tag("sink", "local-file")
                .tag("reason", "IO_ERROR")
                .counter()
                .count()).isEqualTo(1.0d);
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

    private ExternalAuditAnchorPublicationStatusDocument requiredFailedStatus(AuditAnchorDocument anchor) {
        return new ExternalAuditAnchorPublicationStatusDocument(
                anchor.anchorId(),
                anchor.partitionKey(),
                anchor.chainPosition(),
                false,
                ExternalAuditAnchor.STATUS_LOCAL_ANCHOR_CREATED_EXTERNAL_REQUIRED_FAILED,
                ExternalAuditAnchor.STATUS_FAILED,
                null,
                "RECORDED",
                "CREATED",
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                ExternalAuditAnchor.REASON_EXTERNAL_ANCHOR_REQUIRED_FAILED,
                Instant.parse("2026-04-27T10:01:00Z")
        );
    }

    private static class UnavailableTrustAuthorityClient implements AuditTrustAuthorityClient {

        @Override
        public SignedAuditAnchorPayload sign(AuditAnchorSigningPayload payload) {
            return SignedAuditAnchorPayload.unavailable();
        }

        @Override
        public AuditTrustSignatureVerificationResult verify(AuditAnchorSigningPayload payload, SignedAuditAnchorPayload signature) {
            return AuditTrustSignatureVerificationResult.unavailable();
        }

        @Override
        public List<AuditTrustAuthorityKey> keys() {
            return List.of();
        }

        @Override
        public boolean enabled() {
            return true;
        }
    }
}
