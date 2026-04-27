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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditEvidenceExportServiceTest {

    private final AuditEventRepository eventRepository = mock(AuditEventRepository.class);
    private final AuditAnchorRepository anchorRepository = mock(AuditAnchorRepository.class);
    private final ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
    private final AuditService auditService = mock(AuditService.class);
    private final AuditEvidenceExportService service = new AuditEvidenceExportService(
            eventRepository,
            anchorRepository,
            sink,
            new AuditEvidenceExportQueryParser(),
            new AlertServiceMetrics(new SimpleMeterRegistry()),
            auditService
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

        AuditEvidenceExportResponse response = service.export(
                "2026-04-27T00:00:00Z",
                "2026-04-28T00:00:00Z",
                "alert-service",
                null
        );

        assertThat(response.status()).isEqualTo("AVAILABLE");
        assertThat(response.count()).isEqualTo(1);
        assertThat(response.events().getFirst().eventHash()).isEqualTo("hash-1");
        assertThat(response.events().getFirst().localAnchor()).isNotNull();
        assertThat(response.events().getFirst().externalAnchor()).isNotNull();
        assertThat(response.toString()).doesNotContain("raw", "token", "stack");
        verify(auditService).audit(
                eq(AuditAction.EXPORT_AUDIT_EVIDENCE),
                eq(AuditResourceType.AUDIT_EVIDENCE_EXPORT),
                any(),
                any(),
                eq("audit-evidence-exporter"),
                eq(AuditOutcome.SUCCESS),
                any(),
                any(AuditEventMetadataSummary.class)
        );
    }

    @Test
    void shouldRejectUnboundedEvidenceExportQueries() {
        assertQueryError(() -> service.export(null, "2026-04-28T00:00:00Z", "alert-service", 100), "from: is required");
        assertQueryError(() -> service.export("2026-04-27T00:00:00Z", null, "alert-service", 100), "to: is required");
        assertQueryError(() -> service.export("2026-04-27T00:00:00Z", "2026-04-28T00:00:00Z", null, 100), "source_service: is required");
        assertQueryError(() -> service.export("2026-04-27T00:00:00Z", "2026-04-28T00:00:00Z", "alert-service", 501), "limit: must be less than or equal to 500");
    }

    private void assertQueryError(Runnable action, String expectedDetail) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(InvalidAuditEventQueryException.class,
                        exception -> assertThat(exception.details()).contains(expectedDetail));
    }

    private AuditEventDocument event(String auditId, long chainPosition) {
        return new AuditEventDocument(
                auditId,
                AuditAction.SUBMIT_ANALYST_DECISION,
                "admin-1",
                "admin-1",
                List.of("FRAUD_OPS_ADMIN"),
                "HUMAN",
                List.of("audit:export"),
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                Instant.parse("2026-04-27T10:00:00Z"),
                "corr-1",
                "request-1",
                "alert-service",
                "source_service:alert-service",
                chainPosition,
                AuditOutcome.SUCCESS,
                AuditFailureCategory.NONE,
                null,
                AuditEventMetadataSummary.auditRead(null, "alert-service", "1.0", "TEST", "limit=1", 1),
                null,
                "hash-" + chainPosition,
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
}
