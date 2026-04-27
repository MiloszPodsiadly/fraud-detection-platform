package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.AuditAnchorDocument;
import com.frauddetection.alert.audit.AuditAnchorRepository;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalAuditAnchorPublisherTest {

    private final AuditAnchorRepository anchorRepository = mock(AuditAnchorRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AlertServiceMetrics metrics = new AlertServiceMetrics(meterRegistry);

    @Test
    void shouldRecordSinkFailureWithoutThrowing() {
        ExternalAuditAnchorSink sink = new DisabledExternalAuditAnchorSink();
        when(anchorRepository.findByPartitionKeyAndChainPositionGreaterThan("source_service:alert-service", 0L, 100))
                .thenReturn(List.of(localAnchor("local-anchor-1", 1L, "hash-1")));
        ExternalAuditAnchorPublisher publisher = new ExternalAuditAnchorPublisher(
                anchorRepository,
                sink,
                metrics,
                Clock.fixed(Instant.parse("2026-04-27T10:01:00Z"), ZoneOffset.UTC),
                100
        );

        ExternalAuditAnchorPublishResult result = publisher.publishDefaultWindow();

        assertThat(result.failed()).isEqualTo(1);
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
                sink,
                metrics,
                Clock.fixed(Instant.parse("2026-04-27T10:01:00Z"), ZoneOffset.UTC),
                100
        );

        ExternalAuditAnchorPublishResult result = publisher.publishDefaultWindow();

        assertThat(result.published()).isEqualTo(1);
        verify(anchorRepository)
                .findByPartitionKeyAndChainPositionGreaterThan("source_service:alert-service", 1L, 100);
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
                sink,
                metrics,
                Clock.fixed(Instant.parse("2026-04-27T10:01:00Z"), ZoneOffset.UTC),
                100
        );

        ExternalAuditAnchorPublishResult result = publisher.publishDefaultWindow();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.published()).isEqualTo(1);
        assertThat(meterRegistry.get("fraud_platform_audit_external_anchor_publish_failed_total")
                .tag("sink", "local-file")
                .tag("reason", "CONFLICT")
                .counter()
                .count()).isEqualTo(1.0d);
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
}
