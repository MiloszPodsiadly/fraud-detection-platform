package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditAnchorDocument;
import com.frauddetection.alert.audit.AuditAnchorRepository;
import com.frauddetection.alert.audit.AuditEventDocument;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
import com.frauddetection.alert.audit.AuditEventRepository;
import com.frauddetection.alert.audit.AuditFailureCategory;
import com.frauddetection.alert.audit.InvalidAuditEventQueryException;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditEvidenceExportServiceTest {

    private final AuditEventRepository eventRepository = mock(AuditEventRepository.class);
    private final AuditAnchorRepository anchorRepository = mock(AuditAnchorRepository.class);
    private final ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
    private final AuditService auditService = mock(AuditService.class);
    private final CurrentAnalystUser currentAnalystUser = mock(CurrentAnalystUser.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AlertServiceMetrics metrics = new AlertServiceMetrics(meterRegistry);
    private final InMemoryAuditEvidenceExportRateLimiter rateLimiter = new InMemoryAuditEvidenceExportRateLimiter(
            Clock.fixed(Instant.parse("2026-04-27T10:00:00Z"), ZoneOffset.UTC),
            5
    );
    private final AuditEvidenceExportAbuseDetector abuseDetector = new AuditEvidenceExportAbuseDetector(metrics);
    private final AuditEvidenceExportService service = new AuditEvidenceExportService(
            eventRepository,
            anchorRepository,
            sink,
            new AuditEvidenceExportQueryParser(),
            metrics,
            auditService,
            currentAnalystUser,
            rateLimiter,
            abuseDetector
    );

    @Test
    void shouldReturnBoundedEvidenceWithLocalAndExternalAnchorReferencesAndAuditAccess() {
        AuditEventDocument event = event("audit-1", 1L);
        AuditAnchorDocument localAnchor = localAnchor("local-anchor-1", 1L, "hash-1");
        ExternalAuditAnchor externalAnchor = ExternalAuditAnchor.from(localAnchor, "local-file");
        when(eventRepository.findEvidenceWindow(
                eq("alert-service"),
                eq(Instant.parse("2026-04-27T00:00:00Z")),
                eq(Instant.parse("2026-04-28T00:00:00Z")),
                eq(100)
        )).thenReturn(List.of(event));
        when(anchorRepository.findByPartitionKeyAndChainPositionBetween("source_service:alert-service", 1L, 1L, 100))
                .thenReturn(List.of(localAnchor));
        when(sink.findByRange(
                "source_service:alert-service",
                Instant.parse("2026-04-27T00:00:00Z"),
                Instant.parse("2026-04-28T00:00:00Z"),
                100
        )).thenReturn(List.of(externalAnchor));
        when(currentAnalystUser.get()).thenReturn(Optional.of(adminPrincipal()));

        AuditEvidenceExportResponse response = service.export(
                "2026-04-27T00:00:00Z",
                "2026-04-28T00:00:00Z",
                "alert-service",
                null
        );

        assertThat(response.status()).isEqualTo("AVAILABLE");
        assertThat(response.count()).isEqualTo(1);
        assertThat(response.externalAnchorStatus()).isEqualTo("AVAILABLE");
        assertThat(response.anchorCoverage().totalEvents()).isEqualTo(1);
        assertThat(response.anchorCoverage().eventsWithLocalAnchor()).isEqualTo(1);
        assertThat(response.anchorCoverage().eventsWithExternalAnchor()).isEqualTo(1);
        assertThat(response.anchorCoverage().eventsMissingExternalAnchor()).isZero();
        assertThat(response.anchorCoverage().coverageRatio()).isEqualTo(1.0d);
        assertThat(response.exportFingerprint()).hasSize(64);
        assertThat(response.events().getFirst().eventHash()).isEqualTo("hash-1");
        assertThat(response.events().getFirst().localAnchor()).isNotNull();
        assertThat(response.events().getFirst().localAnchor().publicationStatus()).isNull();
        assertThat(response.events().getFirst().externalAnchor()).isNotNull();
        assertThat(response.events().getFirst().businessEffective()).isTrue();
        assertThat(response.events().getFirst().compensated()).isFalse();
        assertThat(response.toString()).doesNotContain("raw", "token", "stack", "private", "secret");
        ArgumentCaptor<AuditEventMetadataSummary> metadata = forClass(AuditEventMetadataSummary.class);
        verify(auditService).audit(
                eq(AuditAction.EXPORT_AUDIT_EVIDENCE),
                eq(AuditResourceType.AUDIT_EVIDENCE_EXPORT),
                any(),
                any(),
                eq("audit-evidence-exporter"),
                eq(AuditOutcome.SUCCESS),
                any(),
                metadata.capture()
        );
        assertThat(metadata.getValue().from()).isEqualTo("2026-04-27T00:00:00Z");
        assertThat(metadata.getValue().to()).isEqualTo("2026-04-28T00:00:00Z");
        assertThat(metadata.getValue().sourceService()).isEqualTo("alert-service");
        assertThat(metadata.getValue().limit()).isEqualTo(100);
        assertThat(metadata.getValue().returnedCount()).isEqualTo(1);
        assertThat(metadata.getValue().exportStatus()).isEqualTo("AVAILABLE");
        assertThat(metadata.getValue().reasonCode()).isNull();
        assertThat(metadata.getValue().externalAnchorStatus()).isEqualTo("AVAILABLE");
        assertThat(metadata.getValue().anchorCoverage().coverageRatio()).isEqualTo(1.0d);
        assertThat(metadata.getValue().exportFingerprint()).isEqualTo(response.exportFingerprint());
    }

    @Test
    void shouldExposeCompensatedAttemptedEventsInEvidenceExport() {
        AuditEventDocument attempted = event("audit-attempted", 1L, AuditAction.SUBMIT_ANALYST_DECISION, AuditResourceType.ALERT,
                "alert-1", AuditOutcome.ATTEMPTED, null, null);
        AuditEventDocument aborted = event("audit-aborted", 2L, AuditAction.EXTERNAL_ANCHOR_REQUIRED_FAILED, AuditResourceType.AUDIT_EVENT,
                "audit-attempted", AuditOutcome.ABORTED_EXTERNAL_ANCHOR_REQUIRED, attempted.eventHash(), "ExternalAuditAnchorPublicationRequiredException:WRITE_NOT_VERIFIED");
        when(eventRepository.findEvidenceWindow(any(), any(), any(), anyInt())).thenReturn(List.of(attempted, aborted));
        when(anchorRepository.findByPartitionKeyAndChainPositionBetween("source_service:alert-service", 1L, 2L, 100))
                .thenReturn(List.of(
                        localAnchor("local-anchor-1", 1L, "hash-1"),
                        localAnchor("local-anchor-2", 2L, "hash-2")
                ));
        when(sink.findByRange(any(), any(), any(), anyInt())).thenReturn(List.of());

        AuditEvidenceExportResponse response = service.export(
                "2026-04-27T00:00:00Z",
                "2026-04-28T00:00:00Z",
                "alert-service",
                null
        );

        AuditEvidenceExportEvent attemptResponse = response.events().getFirst();
        AuditEvidenceExportEvent abortResponse = response.events().get(1);
        assertThat(attemptResponse.compensated()).isTrue();
        assertThat(attemptResponse.supersededByEventId()).isEqualTo("audit-aborted");
        assertThat(attemptResponse.businessEffective()).isFalse();
        assertThat(abortResponse.relatedEventId()).isEqualTo("audit-attempted");
        assertThat(abortResponse.businessEffective()).isFalse();
    }

    @Test
    void shouldReturnPartialWhenExternalAnchorsAreUnavailable() {
        AuditEventDocument event = event("audit-1", 1L);
        AuditAnchorDocument localAnchor = localAnchor("local-anchor-1", 1L, "hash-1");
        when(eventRepository.findEvidenceWindow(any(), any(), any(), anyInt())).thenReturn(List.of(event));
        when(anchorRepository.findByPartitionKeyAndChainPositionBetween("source_service:alert-service", 1L, 1L, 100))
                .thenReturn(List.of(localAnchor));
        when(sink.findByRange(any(), any(), any(), anyInt()))
                .thenThrow(new ExternalAuditAnchorSinkException("IO_ERROR", "filesystem path /internal/detail"));

        AuditEvidenceExportResponse response = service.export(
                "2026-04-27T00:00:00Z",
                "2026-04-28T00:00:00Z",
                "alert-service",
                null
        );

        assertThat(response.status()).isEqualTo("PARTIAL");
        assertThat(response.reasonCode()).isEqualTo("EXTERNAL_ANCHORS_UNAVAILABLE");
        assertThat(response.externalAnchorStatus()).isEqualTo("UNAVAILABLE");
        assertThat(response.message()).doesNotContain("/internal/detail", "filesystem");
        assertThat(response.anchorCoverage().eventsWithLocalAnchor()).isEqualTo(1);
        assertThat(response.anchorCoverage().eventsMissingExternalAnchor()).isEqualTo(1);
        assertThat(response.anchorCoverage().coverageRatio()).isZero();
    }

    @Test
    void shouldReturnPartialWhenExternalAnchorCoverageIsIncomplete() {
        AuditEventDocument first = event("audit-1", 1L);
        AuditEventDocument second = event("audit-2", 2L);
        AuditAnchorDocument firstLocal = localAnchor("local-anchor-1", 1L, "hash-1");
        AuditAnchorDocument secondLocal = localAnchor("local-anchor-2", 2L, "hash-2");
        when(eventRepository.findEvidenceWindow(any(), any(), any(), anyInt())).thenReturn(List.of(first, second));
        when(anchorRepository.findByPartitionKeyAndChainPositionBetween("source_service:alert-service", 1L, 2L, 100))
                .thenReturn(List.of(firstLocal, secondLocal));
        when(sink.findByRange(any(), any(), any(), anyInt()))
                .thenReturn(List.of(ExternalAuditAnchor.from(firstLocal, "local-file")));

        AuditEvidenceExportResponse response = service.export(
                "2026-04-27T00:00:00Z",
                "2026-04-28T00:00:00Z",
                "alert-service",
                null
        );

        assertThat(response.status()).isEqualTo("PARTIAL");
        assertThat(response.reasonCode()).isEqualTo("EXTERNAL_ANCHOR_GAPS");
        assertThat(response.externalAnchorStatus()).isEqualTo("PARTIAL");
        assertThat(response.anchorCoverage().eventsWithExternalAnchor()).isEqualTo(1);
        assertThat(response.anchorCoverage().eventsMissingExternalAnchor()).isEqualTo(1);
        assertThat(response.anchorCoverage().coverageRatio()).isEqualTo(0.5d);
    }

    @Test
    void shouldReturnPartialForLegacyUnanchoredEvents() {
        AuditEventDocument legacy = event("audit-legacy", null);
        when(eventRepository.findEvidenceWindow(any(), any(), any(), anyInt())).thenReturn(List.of(legacy));
        when(sink.findByRange(any(), any(), any(), anyInt())).thenReturn(List.of());

        AuditEvidenceExportResponse response = service.export(
                "2026-04-27T00:00:00Z",
                "2026-04-28T00:00:00Z",
                "alert-service",
                null
        );

        assertThat(response.status()).isEqualTo("PARTIAL");
        assertThat(response.reasonCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.anchorCoverage().eventsWithLocalAnchor()).isZero();
        assertThat(response.anchorCoverage().eventsMissingExternalAnchor()).isEqualTo(1);
    }

    @Test
    void shouldReturnPartialWhenExternalAnchoringIsDisabled() {
        AuditEvidenceExportService disabledService = new AuditEvidenceExportService(
                eventRepository,
                anchorRepository,
                new DisabledExternalAuditAnchorSink(),
                new AuditEvidenceExportQueryParser(),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                auditService,
                currentAnalystUser,
                new InMemoryAuditEvidenceExportRateLimiter(Clock.fixed(Instant.parse("2026-04-27T10:00:00Z"), ZoneOffset.UTC), 5),
                new AuditEvidenceExportAbuseDetector(new AlertServiceMetrics(new SimpleMeterRegistry()))
        );
        AuditEventDocument event = event("audit-1", 1L);
        AuditAnchorDocument localAnchor = localAnchor("local-anchor-1", 1L, "hash-1");
        when(eventRepository.findEvidenceWindow(any(), any(), any(), anyInt())).thenReturn(List.of(event));
        when(anchorRepository.findByPartitionKeyAndChainPositionBetween("source_service:alert-service", 1L, 1L, 100))
                .thenReturn(List.of(localAnchor));

        AuditEvidenceExportResponse response = disabledService.export(
                "2026-04-27T00:00:00Z",
                "2026-04-28T00:00:00Z",
                "alert-service",
                null
        );

        assertThat(response.status()).isEqualTo("PARTIAL");
        assertThat(response.reasonCode()).isEqualTo("EXTERNAL_ANCHORS_UNAVAILABLE");
        assertThat(response.externalAnchorStatus()).isEqualTo("DISABLED");
    }

    @Test
    void shouldReturnAvailableWithFullCoverageForEmptyExport() {
        when(eventRepository.findEvidenceWindow(any(), any(), any(), anyInt())).thenReturn(List.of());

        AuditEvidenceExportResponse response = service.export(
                "2026-04-27T00:00:00Z",
                "2026-04-28T00:00:00Z",
                "alert-service",
                null
        );

        assertThat(response.status()).isEqualTo("AVAILABLE");
        assertThat(response.reasonCode()).isNull();
        assertThat(response.externalAnchorStatus()).isEqualTo("AVAILABLE");
        assertThat(response.anchorCoverage().totalEvents()).isZero();
        assertThat(response.anchorCoverage().coverageRatio()).isEqualTo(1.0d);
        assertThat(response.exportFingerprint()).hasSize(64);
    }

    @Test
    void shouldReturnUnavailableWhenLocalAuditStoreIsUnavailable() {
        when(eventRepository.findEvidenceWindow(any(), any(), any(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("mongo timeout at internal-host"));

        AuditEvidenceExportResponse response = service.export(
                "2026-04-27T00:00:00Z",
                "2026-04-28T00:00:00Z",
                "alert-service",
                null
        );

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        assertThat(response.reasonCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.externalAnchorStatus()).isEqualTo("UNAVAILABLE");
        assertThat(response.events()).isEmpty();
        assertThat(response.message()).doesNotContain("mongo", "internal-host", "timeout");
    }

    @Test
    void shouldKeepEvidenceExportJsonShapeStableAndSafe() throws Exception {
        AuditEventDocument event = event("audit-1", 1L);
        AuditAnchorDocument localAnchor = localAnchor("local-anchor-1", 1L, "hash-1");
        when(eventRepository.findEvidenceWindow(any(), any(), any(), anyInt())).thenReturn(List.of(event));
        when(anchorRepository.findByPartitionKeyAndChainPositionBetween("source_service:alert-service", 1L, 1L, 100))
                .thenReturn(List.of(localAnchor));
        when(sink.findByRange(any(), any(), any(), anyInt()))
                .thenReturn(List.of(ExternalAuditAnchor.from(localAnchor, "local-file")));

        AuditEvidenceExportResponse response = service.export(
                "2026-04-27T00:00:00Z",
                "2026-04-28T00:00:00Z",
                "alert-service",
                null
        );

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        Map<String, Object> json = objectMapper.readValue(
                objectMapper.writeValueAsString(response),
                new TypeReference<>() {
                }
        );
        assertThat(json).containsOnlyKeys(
                "status",
                "count",
                "limit",
                "source_service",
                "from",
                "to",
                "external_anchor_status",
                "anchor_coverage",
                "export_fingerprint",
                "chain_range_start",
                "chain_range_end",
                "partial_chain_range",
                "events"
        );
        assertThat(json).containsEntry("partial_chain_range", false);
        String serialized = objectMapper.writeValueAsString(response);
        assertThat(serialized).doesNotContain("raw_request", "request_payload", "token", "private_key", "stack_trace", "exception_message", "secret");
    }

    @Test
    void shouldRejectUnboundedEvidenceExportQueries() {
        assertQueryError(() -> service.export(null, "2026-04-28T00:00:00Z", "alert-service", 100), "from: is required");
        assertQueryError(() -> service.export("2026-04-27T00:00:00Z", null, "alert-service", 100), "to: is required");
        assertQueryError(() -> service.export("2026-04-27T00:00:00Z", "2026-04-28T00:00:00Z", null, 100), "source_service: is required");
        assertQueryError(() -> service.export("not-a-timestamp", "2026-04-28T00:00:00Z", "alert-service", 100), "from: must be an ISO-8601 timestamp");
        assertQueryError(() -> service.export("2026-04-29T00:00:00Z", "2026-04-28T00:00:00Z", "alert-service", 100), "from: must be before or equal to to");
        assertQueryError(() -> service.export("2026-04-27T00:00:00Z", "2026-04-28T00:00:00Z", "unknown-service", 100), "source_service: unsupported value");
        assertQueryError(() -> service.export("2026-04-27T00:00:00Z", "2026-04-28T00:00:00Z", "alert-service", 0), "limit: must be greater than 0");
        assertQueryError(() -> service.export("2026-04-27T00:00:00Z", "2026-04-28T00:00:00Z", "alert-service", 501), "limit: must be less than or equal to 500");
    }

    @Test
    void shouldRejectStrictExportWhenEvidenceWouldBePartial() {
        AuditEventDocument event = event("audit-1", 1L);
        AuditAnchorDocument localAnchor = localAnchor("local-anchor-1", 1L, "hash-1");
        when(eventRepository.findEvidenceWindow(any(), any(), any(), anyInt())).thenReturn(List.of(event));
        when(anchorRepository.findByPartitionKeyAndChainPositionBetween("source_service:alert-service", 1L, 1L, 100))
                .thenReturn(List.of(localAnchor));
        when(sink.findByRange(any(), any(), any(), anyInt())).thenReturn(List.of());

        assertThatThrownBy(() -> service.export(
                "2026-04-27T00:00:00Z",
                "2026-04-28T00:00:00Z",
                "alert-service",
                null,
                true
        )).isInstanceOfSatisfying(AuditEvidenceExportRejectedException.class, exception -> {
            assertThat(exception.status().value()).isEqualTo(409);
            assertThat(exception.reasonCode()).isEqualTo("EXTERNAL_ANCHORS_UNAVAILABLE");
        });
        ArgumentCaptor<AuditEventMetadataSummary> metadata = forClass(AuditEventMetadataSummary.class);
        verify(auditService).audit(
                eq(AuditAction.EXPORT_AUDIT_EVIDENCE),
                eq(AuditResourceType.AUDIT_EVIDENCE_EXPORT),
                any(),
                any(),
                eq("audit-evidence-exporter"),
                eq(AuditOutcome.FAILED),
                eq("EXTERNAL_ANCHORS_UNAVAILABLE"),
                metadata.capture()
        );
        assertThat(metadata.getValue().exportStatus()).isEqualTo("REJECTED_STRICT_MODE");
        assertThat(metadata.getValue().reasonCode()).isEqualTo("EXTERNAL_ANCHORS_UNAVAILABLE");
        assertThat(metadata.getValue().exportFingerprint()).hasSize(64);
    }

    @Test
    void shouldAllowPartialExportWhenStrictModeIsDisabled() {
        AuditEventDocument event = event("audit-1", 1L);
        AuditAnchorDocument localAnchor = localAnchor("local-anchor-1", 1L, "hash-1");
        when(eventRepository.findEvidenceWindow(any(), any(), any(), anyInt())).thenReturn(List.of(event));
        when(anchorRepository.findByPartitionKeyAndChainPositionBetween("source_service:alert-service", 1L, 1L, 100))
                .thenReturn(List.of(localAnchor));
        when(sink.findByRange(any(), any(), any(), anyInt())).thenReturn(List.of());

        AuditEvidenceExportResponse response = service.export(
                "2026-04-27T00:00:00Z",
                "2026-04-28T00:00:00Z",
                "alert-service",
                null,
                false
        );

        assertThat(response.status()).isEqualTo("PARTIAL");
        assertThat(response.events()).hasSize(1);
    }

    @Test
    void shouldAuditCompleteMetadataForPartialEvidenceExport() {
        AuditEventDocument event = event("audit-1", 1L);
        AuditAnchorDocument localAnchor = localAnchor("local-anchor-1", 1L, "hash-1");
        when(eventRepository.findEvidenceWindow(any(), any(), any(), anyInt())).thenReturn(List.of(event));
        when(anchorRepository.findByPartitionKeyAndChainPositionBetween("source_service:alert-service", 1L, 1L, 100))
                .thenReturn(List.of(localAnchor));
        when(sink.findByRange(any(), any(), any(), anyInt())).thenReturn(List.of());

        AuditEvidenceExportResponse response = service.export(
                "2026-04-27T00:00:00Z",
                "2026-04-28T00:00:00Z",
                "alert-service",
                null
        );

        ArgumentCaptor<AuditEventMetadataSummary> metadata = forClass(AuditEventMetadataSummary.class);
        verify(auditService).audit(
                eq(AuditAction.EXPORT_AUDIT_EVIDENCE),
                eq(AuditResourceType.AUDIT_EVIDENCE_EXPORT),
                any(),
                any(),
                eq("audit-evidence-exporter"),
                eq(AuditOutcome.SUCCESS),
                eq("EXTERNAL_ANCHORS_UNAVAILABLE"),
                metadata.capture()
        );
        AuditEventMetadataSummary value = metadata.getValue();
        assertThat(value.from()).isEqualTo("2026-04-27T00:00:00Z");
        assertThat(value.to()).isEqualTo("2026-04-28T00:00:00Z");
        assertThat(value.sourceService()).isEqualTo("alert-service");
        assertThat(value.limit()).isEqualTo(100);
        assertThat(value.returnedCount()).isEqualTo(response.count());
        assertThat(value.exportStatus()).isEqualTo("PARTIAL");
        assertThat(value.reasonCode()).isEqualTo("EXTERNAL_ANCHORS_UNAVAILABLE");
        assertThat(value.externalAnchorStatus()).isEqualTo("PARTIAL");
        assertThat(value.anchorCoverage()).isNotNull();
        assertThat(value.anchorCoverage().totalEvents()).isEqualTo(1);
        assertThat(value.anchorCoverage().eventsWithLocalAnchor()).isEqualTo(1);
        assertThat(value.anchorCoverage().eventsWithExternalAnchor()).isZero();
        assertThat(value.anchorCoverage().eventsMissingExternalAnchor()).isEqualTo(1);
        assertThat(value.anchorCoverage().coverageRatio()).isZero();
        assertThat(value.exportFingerprint()).isEqualTo(response.exportFingerprint());
    }

    @Test
    void shouldRecordRepeatedFingerprintMetricForSameActor() {
        when(currentAnalystUser.get()).thenReturn(Optional.of(adminPrincipal()));
        when(eventRepository.findEvidenceWindow(any(), any(), any(), anyInt())).thenReturn(List.of());

        service.export("2026-04-27T00:00:00Z", "2026-04-28T00:00:00Z", "alert-service", null);
        service.export("2026-04-27T00:00:00Z", "2026-04-28T00:00:00Z", "alert-service", null);

        assertThat(meterRegistry.get("fraud_platform_audit_evidence_export_repeated_fingerprint_total")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void shouldRateLimitRepeatedExportsByActorAndAuditRejectedAttempt() {
        CurrentAnalystUser rateLimitedUser = mock(CurrentAnalystUser.class);
        when(rateLimitedUser.get()).thenReturn(Optional.of(adminPrincipal()));
        AuditEvidenceExportService rateLimitedService = new AuditEvidenceExportService(
                eventRepository,
                anchorRepository,
                sink,
                new AuditEvidenceExportQueryParser(),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                auditService,
                rateLimitedUser,
                new InMemoryAuditEvidenceExportRateLimiter(Clock.fixed(Instant.parse("2026-04-27T10:00:00Z"), ZoneOffset.UTC), 1),
                new AuditEvidenceExportAbuseDetector(new AlertServiceMetrics(new SimpleMeterRegistry()))
        );
        when(eventRepository.findEvidenceWindow(any(), any(), any(), anyInt())).thenReturn(List.of());

        rateLimitedService.export("2026-04-27T00:00:00Z", "2026-04-28T00:00:00Z", "alert-service", null);

        assertThatThrownBy(() -> rateLimitedService.export(
                "2026-04-27T00:00:00Z",
                "2026-04-28T00:00:00Z",
                "alert-service",
                null
        )).isInstanceOfSatisfying(AuditEvidenceExportRejectedException.class, exception -> {
            assertThat(exception.status().value()).isEqualTo(429);
            assertThat(exception.reasonCode()).isEqualTo("RATE_LIMITED");
        });
        verify(auditService).audit(
                eq(AuditAction.EXPORT_AUDIT_EVIDENCE),
                eq(AuditResourceType.AUDIT_EVIDENCE_EXPORT),
                any(),
                any(),
                eq("audit-evidence-exporter"),
                eq(AuditOutcome.FAILED),
                eq("RATE_LIMITED"),
                any(AuditEventMetadataSummary.class)
        );
    }

    private void assertQueryError(Runnable action, String expectedDetail) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(InvalidAuditEventQueryException.class,
                        exception -> assertThat(exception.details()).contains(expectedDetail));
    }

    private AuditEventDocument event(String auditId, Long chainPosition) {
        return event(auditId, chainPosition, AuditAction.SUBMIT_ANALYST_DECISION, AuditResourceType.ALERT, "alert-1",
                AuditOutcome.SUCCESS, null, null);
    }

    private AuditEventDocument event(
            String auditId,
            Long chainPosition,
            AuditAction action,
            AuditResourceType resourceType,
            String resourceId,
            AuditOutcome outcome,
            String previousEventHash,
            String failureReason
    ) {
        return new AuditEventDocument(
                auditId,
                action,
                "admin-1",
                "admin-1",
                List.of("FRAUD_OPS_ADMIN"),
                "HUMAN",
                List.of("audit:export"),
                action,
                resourceType,
                resourceId,
                Instant.parse("2026-04-27T10:00:00Z"),
                "corr-1",
                "request-1",
                "alert-service",
                "source_service:alert-service",
                chainPosition,
                outcome,
                outcome == AuditOutcome.SUCCESS || outcome == AuditOutcome.ATTEMPTED
                        ? AuditFailureCategory.NONE
                        : AuditFailureCategory.DEPENDENCY,
                failureReason,
                AuditEventMetadataSummary.auditRead(null, "alert-service", "1.0", "TEST", "limit=1", 1),
                previousEventHash,
                chainPosition == null ? "hash-legacy" : "hash-" + chainPosition,
                "SHA-256",
                "1.0"
        );
    }

    private AuditAnchorDocument localAnchor(String anchorId, long chainPosition, String hash) {
        return new AuditAnchorDocument(
                anchorId,
                Instant.parse("2026-04-27T10:00:01Z"),
                "source_service:alert-service",
                hash,
                chainPosition,
                "SHA-256"
        );
    }

    private AnalystPrincipal adminPrincipal() {
        return new AnalystPrincipal(
                "admin-1",
                Set.of(AnalystRole.FRAUD_OPS_ADMIN),
                Set.of("audit:export")
        );
    }
}
