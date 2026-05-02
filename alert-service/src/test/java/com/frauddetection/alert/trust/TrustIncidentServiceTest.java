package com.frauddetection.alert.trust;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditPersistenceUnavailableException;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.audit.ResolutionEvidenceType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrustIncidentServiceTest {

    @Test
    void shouldAcknowledgeWithFailClosedAudit() {
        TrustIncidentRepository repository = mock(TrustIncidentRepository.class);
        AuditService auditService = mock(AuditService.class);
        TrustIncidentService service = new TrustIncidentService(repository, new TrustIncidentPolicy(), auditService);
        TrustIncidentDocument incident = incident();

        when(repository.findById("incident-1")).thenReturn(Optional.of(incident));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TrustIncidentResponse response = service.acknowledge("incident-1", new TrustIncidentAcknowledgementRequest("seen"), "ops-1");

        assertThat(response.status()).isEqualTo("ACKNOWLEDGED");
        verify(auditService).audit(
                AuditAction.ACK_TRUST_INCIDENT,
                AuditResourceType.TRUST_INCIDENT,
                "incident-1",
                null,
                "ops-1",
                AuditOutcome.SUCCESS,
                null
        );
    }

    @Test
    void shouldNotAcknowledgeWhenAuditFails() {
        TrustIncidentRepository repository = mock(TrustIncidentRepository.class);
        AuditService auditService = mock(AuditService.class);
        TrustIncidentService service = new TrustIncidentService(repository, new TrustIncidentPolicy(), auditService);
        TrustIncidentDocument incident = incident();

        when(repository.findById("incident-1")).thenReturn(Optional.of(incident));
        doThrow(new AuditPersistenceUnavailableException()).when(auditService).audit(
                AuditAction.ACK_TRUST_INCIDENT,
                AuditResourceType.TRUST_INCIDENT,
                "incident-1",
                null,
                "ops-1",
                AuditOutcome.SUCCESS,
                null
        );

        assertThatThrownBy(() -> service.acknowledge("incident-1", new TrustIncidentAcknowledgementRequest("seen"), "ops-1"))
                .isInstanceOf(AuditPersistenceUnavailableException.class);
        verify(repository, never()).save(any());
        assertThat(incident.getStatus()).isEqualTo(TrustIncidentStatus.OPEN);
    }

    @Test
    void shouldResolveWithEvidenceAndAudit() {
        TrustIncidentRepository repository = mock(TrustIncidentRepository.class);
        AuditService auditService = mock(AuditService.class);
        TrustIncidentService service = new TrustIncidentService(repository, new TrustIncidentPolicy(), auditService);
        TrustIncidentDocument incident = incident();

        when(repository.findById("incident-1")).thenReturn(Optional.of(incident));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TrustIncidentResponse response = service.resolve("incident-1", new TrustIncidentResolutionRequest(
                "fixed",
                evidence(),
                false
        ), "ops-2");

        assertThat(response.status()).isEqualTo("RESOLVED");
        assertThat(response.resolutionEvidence()).isEqualTo(evidence());
        verify(auditService).audit(
                AuditAction.RESOLVE_TRUST_INCIDENT,
                AuditResourceType.TRUST_INCIDENT,
                "incident-1",
                null,
                "ops-2",
                AuditOutcome.SUCCESS,
                null
        );
    }

    @Test
    void shouldUpsertSignalsAndSummarizeOpenCriticalIncidents() {
        TrustIncidentRepository repository = mock(TrustIncidentRepository.class);
        TrustIncidentPolicy policy = new TrustIncidentPolicy();
        TrustIncidentService service = new TrustIncidentService(repository, policy, mock(AuditService.class));

        when(repository.findFirstByTypeAndSourceAndFingerprintAndStatusInOrderByUpdatedAtDesc(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.countByStatusInAndSeverity(policy.openStatuses(), TrustIncidentSeverity.CRITICAL)).thenReturn(1L);
        when(repository.countByStatusInAndSeverity(policy.openStatuses(), TrustIncidentSeverity.HIGH)).thenReturn(0L);
        when(repository.countByStatusInAndSeverityAndAcknowledgedAtIsNull(policy.openStatuses(), TrustIncidentSeverity.CRITICAL)).thenReturn(1L);
        when(repository.findTopByStatusInOrderByFirstSeenAtAsc(policy.openStatuses())).thenReturn(Optional.of(incident()));
        when(repository.findTop100ByStatusInOrderByUpdatedAtDesc(policy.openStatuses())).thenReturn(List.of(incident()));

        TrustIncidentSummary summary = service.summary(List.of(new TrustSignal(
                "OUTBOX_TERMINAL_FAILURE",
                TrustIncidentSeverity.CRITICAL,
                "transactional_outbox",
                "status=FAILED_TERMINAL",
                List.of("outbox:event-1")
        )));

        assertThat(summary.openCriticalIncidentCount()).isEqualTo(1L);
        assertThat(summary.incidentHealthStatus()).isEqualTo("CRITICAL");
        assertThat(summary.topIncidentTypes()).containsExactly("OUTBOX_TERMINAL_FAILURE");
    }

    private TrustIncidentDocument incident() {
        TrustIncidentDocument document = new TrustIncidentDocument();
        document.setIncidentId("incident-1");
        document.setType("OUTBOX_TERMINAL_FAILURE");
        document.setSeverity(TrustIncidentSeverity.CRITICAL);
        document.setSource("transactional_outbox");
        document.setFingerprint("fingerprint");
        document.setStatus(TrustIncidentStatus.OPEN);
        document.setFirstSeenAt(Instant.parse("2026-05-02T10:00:00Z"));
        document.setLastSeenAt(Instant.parse("2026-05-02T10:00:00Z"));
        document.setOccurrenceCount(1);
        document.setEvidenceRefs(List.of("outbox:event-1"));
        return document;
    }

    private ResolutionEvidenceReference evidence() {
        return new ResolutionEvidenceReference(
                ResolutionEvidenceType.RUNBOOK_STEP,
                "runbook-1",
                Instant.parse("2026-05-02T10:05:00Z"),
                "ops-2"
        );
    }
}
