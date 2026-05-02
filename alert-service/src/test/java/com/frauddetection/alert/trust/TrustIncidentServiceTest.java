package com.frauddetection.alert.trust;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.audit.ResolutionEvidenceType;
import com.frauddetection.alert.regulated.RegulatedMutationCommand;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationExecutionContext;
import com.frauddetection.alert.regulated.RegulatedMutationResult;
import com.frauddetection.alert.regulated.RegulatedMutationState;
import com.frauddetection.alert.regulated.mutation.trustincident.TrustIncidentAcknowledgeMutationHandler;
import com.frauddetection.alert.regulated.mutation.trustincident.TrustIncidentResolveMutationHandler;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrustIncidentServiceTest {

    @Test
    void shouldListOpenWithoutMaterializingSignals() {
        TrustIncidentRepository repository = mock(TrustIncidentRepository.class);
        TrustIncidentService service = service(repository, mock(RegulatedMutationCoordinator.class));
        when(repository.findTop100ByStatusInOrderByUpdatedAtDesc(new TrustIncidentPolicy().openStatuses()))
                .thenReturn(List.of(incident()));

        List<TrustIncidentResponse> response = service.listOpen();

        assertThat(response).hasSize(1);
        verify(repository, never()).save(any());
    }

    @Test
    void shouldSummarizeWithoutWritingIncidents() {
        TrustIncidentRepository repository = mock(TrustIncidentRepository.class);
        TrustIncidentPolicy policy = new TrustIncidentPolicy();
        TrustIncidentService service = service(repository, mock(RegulatedMutationCoordinator.class));

        when(repository.countByStatusInAndSeverity(policy.openStatuses(), TrustIncidentSeverity.CRITICAL)).thenReturn(1L);
        when(repository.countByStatusInAndSeverity(policy.openStatuses(), TrustIncidentSeverity.HIGH)).thenReturn(0L);
        when(repository.countByStatusInAndSeverityAndAcknowledgedAtIsNull(policy.openStatuses(), TrustIncidentSeverity.CRITICAL)).thenReturn(1L);
        when(repository.findTopByStatusInOrderByFirstSeenAtAsc(policy.openStatuses())).thenReturn(Optional.of(incident()));
        when(repository.findTop100ByStatusInOrderByUpdatedAtDesc(policy.openStatuses())).thenReturn(List.of(incident()));

        TrustIncidentSummary summary = service.summary();

        assertThat(summary.openCriticalIncidentCount()).isEqualTo(1L);
        assertThat(summary.incidentHealthStatus()).isEqualTo("CRITICAL");
        assertThat(summary.topIncidentTypes()).containsExactly("OUTBOX_TERMINAL_FAILURE");
        verify(repository, never()).save(any());
    }

    @Test
    void shouldRouteAcknowledgeThroughRegulatedCoordinator() {
        TrustIncidentRepository repository = mock(TrustIncidentRepository.class);
        RegulatedMutationCoordinator coordinator = mock(RegulatedMutationCoordinator.class);
        TrustIncidentService service = service(repository, coordinator);
        TrustIncidentDocument incident = incident();

        when(coordinator.commit(any())).thenAnswer(invocation -> {
            RegulatedMutationCommand<TrustIncidentDocument, TrustIncidentResponse> command = invocation.getArgument(0);
            TrustIncidentResponse response = TrustIncidentResponse.from(incident);
            return new RegulatedMutationResult<>(RegulatedMutationState.EVIDENCE_PENDING, response);
        });

        TrustIncidentResponse response = service.acknowledge(
                "incident-1",
                new TrustIncidentAcknowledgementRequest("seen"),
                "ops-1",
                "ack-1"
        );

        assertThat(response.incidentId()).isEqualTo("incident-1");
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<RegulatedMutationCommand<TrustIncidentDocument, TrustIncidentResponse>> captor =
                org.mockito.ArgumentCaptor.forClass(RegulatedMutationCommand.class);
        verify(coordinator).commit(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(AuditAction.ACK_TRUST_INCIDENT);
        assertThat(captor.getValue().resourceType()).isEqualTo(AuditResourceType.TRUST_INCIDENT);
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("ack-1");
    }

    @Test
    void shouldAuditRefreshBeforeAndAfterMaterialization() {
        TrustIncidentRepository repository = mock(TrustIncidentRepository.class);
        TrustIncidentMaterializer materializer = mock(TrustIncidentMaterializer.class);
        AuditService auditService = mock(AuditService.class);
        TrustIncidentService service = new TrustIncidentService(
                repository,
                new TrustIncidentPolicy(),
                mock(RegulatedMutationCoordinator.class),
                mock(TrustIncidentAcknowledgeMutationHandler.class),
                mock(TrustIncidentResolveMutationHandler.class),
                materializer,
                auditService
        );
        when(materializer.materialize(any())).thenReturn(new TrustIncidentMaterializationResponse("AVAILABLE", 1, 1, List.of()));

        service.refresh(List.of(new TrustSignal("OUTBOX_TERMINAL_FAILURE", TrustIncidentSeverity.CRITICAL, "outbox", "fp", List.of())), "ops-1");

        verify(auditService).audit(AuditAction.REFRESH_TRUST_INCIDENTS, AuditResourceType.TRUST_INCIDENT, "trust-incidents", null, "ops-1", AuditOutcome.ATTEMPTED, null);
        verify(auditService).audit(AuditAction.REFRESH_TRUST_INCIDENTS, AuditResourceType.TRUST_INCIDENT, "trust-incidents", null, "ops-1", AuditOutcome.SUCCESS, null);
    }

    private TrustIncidentService service(TrustIncidentRepository repository, RegulatedMutationCoordinator coordinator) {
        return new TrustIncidentService(
                repository,
                new TrustIncidentPolicy(),
                coordinator,
                mock(TrustIncidentAcknowledgeMutationHandler.class),
                mock(TrustIncidentResolveMutationHandler.class),
                mock(TrustIncidentMaterializer.class),
                mock(AuditService.class)
        );
    }

    private TrustIncidentDocument incident() {
        TrustIncidentDocument document = new TrustIncidentDocument();
        document.setIncidentId("incident-1");
        document.setType("OUTBOX_TERMINAL_FAILURE");
        document.setSeverity(TrustIncidentSeverity.CRITICAL);
        document.setSource("transactional_outbox");
        document.setFingerprint("fingerprint");
        document.setActiveDedupeKey("OUTBOX_TERMINAL_FAILURE:transactional_outbox:fingerprint");
        document.setStatus(TrustIncidentStatus.OPEN);
        document.setFirstSeenAt(Instant.parse("2026-05-02T10:00:00Z"));
        document.setLastSeenAt(Instant.parse("2026-05-02T10:00:00Z"));
        document.setOccurrenceCount(1);
        document.setEvidenceRefs(List.of("outbox:event-1"));
        return document;
    }

    @SuppressWarnings("unused")
    private ResolutionEvidenceReference evidence() {
        return new ResolutionEvidenceReference(
                ResolutionEvidenceType.RUNBOOK_STEP,
                "runbook-1",
                Instant.parse("2026-05-02T10:05:00Z"),
                "ops-2"
        );
    }
}
