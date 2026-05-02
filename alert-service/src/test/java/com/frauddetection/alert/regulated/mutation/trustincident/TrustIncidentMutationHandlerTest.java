package com.frauddetection.alert.regulated.mutation.trustincident;

import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.audit.ResolutionEvidenceType;
import com.frauddetection.alert.trust.TrustIncidentAcknowledgementRequest;
import com.frauddetection.alert.trust.TrustIncidentDocument;
import com.frauddetection.alert.trust.TrustIncidentPolicy;
import com.frauddetection.alert.trust.TrustIncidentRepository;
import com.frauddetection.alert.trust.TrustIncidentResolutionRequest;
import com.frauddetection.alert.trust.TrustIncidentSeverity;
import com.frauddetection.alert.trust.TrustIncidentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrustIncidentMutationHandlerTest {

    @Test
    void shouldPersistAcknowledgementReason() {
        TrustIncidentRepository repository = mock(TrustIncidentRepository.class);
        TrustIncidentDocument incident = incident();
        when(repository.findById("incident-1")).thenReturn(Optional.of(incident));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TrustIncidentDocument saved = new TrustIncidentAcknowledgeMutationHandler(repository, new TrustIncidentPolicy())
                .acknowledge("incident-1", new TrustIncidentAcknowledgementRequest("operator reviewed"), "ops-1");

        assertThat(saved.getStatus()).isEqualTo(TrustIncidentStatus.ACKNOWLEDGED);
        assertThat(saved.getAcknowledgementReason()).isEqualTo("operator reviewed");
        assertThat(saved.getAcknowledgedBy()).isEqualTo("ops-1");
    }

    @Test
    void shouldRestoreAcknowledgementStateWhenSaveFails() {
        TrustIncidentRepository repository = mock(TrustIncidentRepository.class);
        TrustIncidentDocument incident = incident();
        when(repository.findById("incident-1")).thenReturn(Optional.of(incident));
        when(repository.save(any())).thenThrow(new DataAccessResourceFailureException("mongo down"));

        assertThatThrownBy(() -> new TrustIncidentAcknowledgeMutationHandler(repository, new TrustIncidentPolicy())
                .acknowledge("incident-1", new TrustIncidentAcknowledgementRequest("operator reviewed"), "ops-1"))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(incident.getStatus()).isEqualTo(TrustIncidentStatus.OPEN);
        assertThat(incident.getAcknowledgementReason()).isNull();
        assertThat(incident.getAcknowledgedBy()).isNull();
    }

    @Test
    void shouldRestoreResolveStateWhenSaveFails() {
        TrustIncidentRepository repository = mock(TrustIncidentRepository.class);
        TrustIncidentDocument incident = incident();
        when(repository.findById("incident-1")).thenReturn(Optional.of(incident));
        when(repository.save(any())).thenThrow(new DataAccessResourceFailureException("mongo down"));

        assertThatThrownBy(() -> new TrustIncidentResolveMutationHandler(repository, new TrustIncidentPolicy())
                .resolve("incident-1", new TrustIncidentResolutionRequest("fixed", evidence(), false), "ops-2"))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(incident.getStatus()).isEqualTo(TrustIncidentStatus.OPEN);
        assertThat(incident.getActiveDedupeKey()).isEqualTo("OUTBOX_TERMINAL_FAILURE:transactional_outbox:fingerprint");
        assertThat(incident.getResolvedBy()).isNull();
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
