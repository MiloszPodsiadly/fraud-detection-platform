package com.frauddetection.alert.audit.read.failure;

import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.external.AuditEvidenceExportController;
import com.frauddetection.alert.audit.external.AuditEvidenceExportResponse;
import com.frauddetection.alert.audit.external.AuditEvidenceExportService;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorCoverageResponse;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorSink;
import com.frauddetection.alert.audit.external.ExternalAuditIntegrityService;
import com.frauddetection.alert.audit.external.ExternalDurabilityGuarantee;
import com.frauddetection.alert.audit.external.ExternalImmutabilityLevel;
import com.frauddetection.alert.audit.external.ExternalWitnessCapabilities;
import com.frauddetection.alert.audit.external.ExternalWitnessTimestampType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.outbox.OutboxBacklogResponse;
import com.frauddetection.alert.outbox.OutboxRecoveryController;
import com.frauddetection.alert.outbox.OutboxRecoveryService;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.regulated.RegulatedMutationCommandInspectionResponse;
import com.frauddetection.alert.regulated.RegulatedMutationInspectionRateLimiter;
import com.frauddetection.alert.regulated.RegulatedMutationRecoveryBacklogResponse;
import com.frauddetection.alert.regulated.RegulatedMutationRecoveryController;
import com.frauddetection.alert.regulated.RegulatedMutationRecoveryService;
import com.frauddetection.alert.system.SystemTrustLevelController;
import com.frauddetection.alert.trust.TrustIncidentController;
import com.frauddetection.alert.trust.TrustIncidentPreviewRateLimiter;
import com.frauddetection.alert.trust.TrustIncidentService;
import com.frauddetection.alert.trust.TrustSignalCollector;
import com.frauddetection.alert.trust.TrustIncidentSummary;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("failure-injection")
@Tag("invariant-proof")
class SensitiveReadEndpointFailClosedControllerTest {

    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final SensitiveReadAuditService sensitiveReadAuditService = mock(SensitiveReadAuditService.class);

    @Test
    void shouldFailClosedWhenTrustLevelReadAuditFailsInBankMode() {
        failAudit();
        SystemTrustLevelController controller = systemTrustLevelController();

        assertFailClosed(() -> controller.trustLevel(request));
    }

    @Test
    void shouldFailClosedWhenTrustIncidentListAuditFailsInBankMode() {
        failAudit();
        TrustIncidentService service = mock(TrustIncidentService.class);
        when(service.listOpen()).thenReturn(List.of());
        TrustIncidentController controller = new TrustIncidentController(
                service,
                mock(TrustSignalCollector.class),
                new TrustIncidentPreviewRateLimiter(30),
                sensitiveReadAuditService
        );

        assertFailClosed(() -> controller.listOpen(request));
    }

    @Test
    void shouldFailClosedWhenRegulatedMutationInspectionAuditFailsInBankMode() {
        failAudit();
        RegulatedMutationRecoveryService service = mock(RegulatedMutationRecoveryService.class);
        when(service.inspect("idem-raw-secret")).thenReturn(new RegulatedMutationCommandInspectionResponse(
                "hash",
                "idem-r...cret",
                "SUBMIT_ANALYST_DECISION",
                "ALERT",
                "alert-1",
                "EVIDENCE_PENDING",
                "COMPLETED",
                null,
                null,
                true,
                "attempted-audit",
                "success-audit",
                null,
                null,
                null,
                Instant.parse("2026-05-03T10:00:00Z")
        ));
        RegulatedMutationRecoveryController controller = new RegulatedMutationRecoveryController(
                service,
                new RegulatedMutationInspectionRateLimiter(30),
                sensitiveReadAuditService
        );

        assertFailClosed(() -> controller.inspect("idem-raw-secret", auth(), request));
    }

    @Test
    void shouldFailClosedWhenOutboxBacklogAuditFailsInBankMode() {
        failAudit();
        OutboxRecoveryService service = mock(OutboxRecoveryService.class);
        when(service.backlog()).thenReturn(new OutboxBacklogResponse(1, 0, 0, 0, 0, 0, 0, 0, 60L));
        OutboxRecoveryController controller = new OutboxRecoveryController(service, sensitiveReadAuditService);

        assertFailClosed(() -> controller.backlog(request));
    }

    @Test
    void shouldFailClosedWhenAuditEvidenceExportAuditFailsInBankMode() {
        failAudit();
        AuditEvidenceExportService service = mock(AuditEvidenceExportService.class);
        when(service.export("2026-05-03T00:00:00Z", "2026-05-03T01:00:00Z", "alert-service", 50, false))
                .thenReturn(new AuditEvidenceExportResponse(
                        "AVAILABLE",
                        0,
                        50,
                        "alert-service",
                        Instant.parse("2026-05-03T00:00:00Z"),
                        Instant.parse("2026-05-03T01:00:00Z"),
                        null,
                        null,
                        "VALID",
                        AuditEvidenceExportResponse.AnchorCoverage.empty(),
                        List.of()
                ));
        AuditEvidenceExportController controller = new AuditEvidenceExportController(service, sensitiveReadAuditService);

        assertFailClosed(() -> controller.exportEvidence(
                "2026-05-03T00:00:00Z",
                "2026-05-03T01:00:00Z",
                "alert-service",
                50,
                false,
                request
        ));
    }

    private void failAudit() {
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Sensitive read audit unavailable."))
                .when(sensitiveReadAuditService)
                .audit(any(), any(), any(), any(), any());
    }

    private void assertFailClosed(ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException responseStatusException = (ResponseStatusException) exception;
                    assertThat(responseStatusException.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(responseStatusException.getReason()).isEqualTo("Sensitive read audit unavailable.");
                });
    }

    private TestingAuthenticationToken auth() {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken("ops-admin", "n/a");
        authentication.setAuthenticated(true);
        return authentication;
    }

    private SystemTrustLevelController systemTrustLevelController() {
        ExternalAuditIntegrityService integrityService = mock(ExternalAuditIntegrityService.class);
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AuditDegradationService degradationService = mock(AuditDegradationService.class);
        AlertRepository alertRepository = mock(AlertRepository.class);
        TransactionalOutboxRecordRepository outboxRepository = mock(TransactionalOutboxRecordRepository.class);
        RegulatedMutationRecoveryService recoveryService = mock(RegulatedMutationRecoveryService.class);
        TrustIncidentService trustIncidentService = mock(TrustIncidentService.class);
        when(integrityService.coverage("alert-service", 100)).thenReturn(new ExternalAuditAnchorCoverageResponse(
                "AVAILABLE",
                10,
                10,
                0,
                0L,
                List.of(),
                false,
                100,
                null,
                null
        ));
        when(sink.capabilities()).thenReturn(new ExternalWitnessCapabilities(
                "OBJECT_STORE",
                "object-store",
                "CROSS_ORG",
                ExternalImmutabilityLevel.ENFORCED,
                true,
                true,
                true,
                true,
                ExternalWitnessTimestampType.STORAGE_OBSERVED,
                "STRONG",
                true,
                true,
                true,
                ExternalDurabilityGuarantee.LEDGER
        ));
        when(degradationService.unresolvedPostCommitDegradedCount()).thenReturn(0L);
        when(degradationService.pendingResolutionCount()).thenReturn(0L);
        when(degradationService.resolvedCount()).thenReturn(0L);
        when(outboxRepository.countByStatus(any(TransactionalOutboxStatus.class))).thenReturn(0L);
        when(outboxRepository.countByProjectionMismatchTrue()).thenReturn(0L);
        when(outboxRepository.findTopByStatusInOrderByCreatedAtAsc(any())).thenReturn(Optional.empty());
        when(trustIncidentService.summary()).thenReturn(TrustIncidentSummary.empty());
        return new SystemTrustLevelController(
                true,
                true,
                true,
                true,
                true,
                true,
                Duration.ofMinutes(10),
                "REQUIRED",
                true,
                true,
                integrityService,
                sink,
                degradationService,
                alertRepository,
                provider(outboxRepository),
                recoveryService,
                provider(trustIncidentService),
                provider(null),
                provider(sensitiveReadAuditService)
        );
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private interface ThrowingCall {
        void run();
    }
}
