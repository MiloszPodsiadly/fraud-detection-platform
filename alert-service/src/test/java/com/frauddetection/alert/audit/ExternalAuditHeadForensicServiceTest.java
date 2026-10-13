package com.frauddetection.alert.audit;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalAuditHeadForensicServiceTest {

    @Test
    void shouldPersistForensicEvidenceWithStableFingerprintAndDistinctEpisodeIdentity() {
        AuditDegradationEventRepository repository = mock(AuditDegradationEventRepository.class);
        ExternalAuditHeadForensicService service = service(repository);
        Instant detectedAt = Instant.parse("2026-10-11T12:00:00Z");

        String incidentId = service.recordIncident(
                "source_service:alert-service",
                "version-7",
                "a".repeat(64),
                "HEAD_MANIFEST_HASH_MISMATCH",
                detectedAt
        );
        org.mockito.ArgumentCaptor<AuditDegradationEventDocument> captor =
                org.mockito.ArgumentCaptor.forClass(AuditDegradationEventDocument.class);
        verify(repository).insert(captor.capture());
        AuditDegradationEventDocument evidence = captor.getValue();
        assertThat(incidentId).startsWith("external-head-");
        assertThat(evidence.getAuditId()).isEqualTo(incidentId);
        assertThat(evidence.getType()).isEqualTo(AuditDegradationEventDocument.TYPE_EXTERNAL_HEAD_INTEGRITY);
        assertThat(evidence.getResourceId()).isEqualTo("source_service:alert-service");
        assertThat(evidence.getObservedVersion()).isEqualTo("version-7");
        assertThat(evidence.getObservedDigest()).hasSize(64);
        assertThat(evidence.getReason()).isEqualTo("HEAD_MANIFEST_HASH_MISMATCH");
        assertThat(evidence.getRecoveryOutcome()).isEqualTo("DETECTED_PENDING_REPAIR");
        assertThat(evidence.getIncidentFingerprint()).matches("[0-9a-f]{64}");
        assertThat(evidence.getActiveIncidentKey()).isEqualTo(evidence.getIncidentFingerprint());
        assertThat(evidence.isResolved()).isFalse();
    }

    @Test
    void shouldTreatDuplicateEvidenceAsIdempotent() {
        AuditDegradationEventRepository repository = mock(AuditDegradationEventRepository.class);
        ExternalAuditHeadForensicService service = service(repository);
        when(repository.insert(any(AuditDegradationEventDocument.class)))
                .thenThrow(new DuplicateKeyException("already recorded"));
        AuditDegradationEventDocument active = new AuditDegradationEventDocument();
        active.setAuditId("external-head-existing-episode");
        when(repository.findByActiveIncidentKey(any())).thenReturn(java.util.Optional.of(active));

        String incidentId = service.recordIncident(
                "source_service:alert-service",
                null,
                null,
                "HEAD_MANIFEST_MISSING",
                Instant.parse("2026-10-11T12:00:00Z")
        );

        assertThat(incidentId).isEqualTo("external-head-existing-episode");
    }

    @Test
    void shouldFailClosedWhenEvidenceCannotBePersisted() {
        AuditDegradationEventRepository repository = mock(AuditDegradationEventRepository.class);
        ExternalAuditHeadForensicService service = service(repository);
        when(repository.insert(any(AuditDegradationEventDocument.class)))
                .thenThrow(new DataAccessResourceFailureException("mongo down"));

        assertThatThrownBy(() -> service.recordIncident(
                "source_service:alert-service",
                "version-7",
                "digest",
                "HEAD_MANIFEST_MALFORMED",
                Instant.parse("2026-10-11T12:00:00Z")
        )).isInstanceOf(AuditPersistenceUnavailableException.class);
    }

    @Test
    void shouldDelegateVerifiedRepairWithoutMutatingStaleDetectionObject() {
        AuditDegradationEventRepository repository = mock(AuditDegradationEventRepository.class);
        ExternalAuditHeadRecoveryTransitions recoveryTransitions = mock(ExternalAuditHeadRecoveryTransitions.class);
        ExternalAuditHeadForensicService service = new ExternalAuditHeadForensicService(
                repository,
                recoveryTransitions
        );
        AuditDegradationEventDocument event = new AuditDegradationEventDocument();
        event.setAuditId("external-head-1");
        event.setReason("HEAD_MANIFEST_HASH_MISMATCH");
        event.setObservedVersion("version-7");
        event.setObservedDigest("digest-7");
        service.recordVerifiedRepair(
                "external-head-1",
                Instant.parse("2026-10-11T12:01:00Z")
        );

        verify(recoveryTransitions).recordVerifiedRepair(
                "external-head-1",
                Instant.parse("2026-10-11T12:01:00Z")
        );
        assertThat(event.isResolved()).isFalse();
        assertThat(event.getReason()).isEqualTo("HEAD_MANIFEST_HASH_MISMATCH");
        assertThat(event.getObservedVersion()).isEqualTo("version-7");
        assertThat(event.getObservedDigest()).isEqualTo("digest-7");
        verify(repository, org.mockito.Mockito.never()).save(event);
    }

    @Test
    void shouldExposeOnlyBoundedForensicMetadataThroughExistingDegradationResponse() {
        AuditDegradationEventDocument event = new AuditDegradationEventDocument();
        event.setAuditId("external-head-1");
        event.setReason("HEAD_MANIFEST_MALFORMED");
        event.setObservedVersion("version-7");
        event.setObservedDigest("digest-7");
        event.setRecoveryAction("RECONSTRUCT_AND_CAS_HEAD");
        event.setRecoveryOutcome("CAS_CONFLICT");
        event.setRecoveryAttemptedAt(Instant.parse("2026-10-11T12:01:00Z"));

        AuditDegradationController.AuditDegradationResponse response =
                AuditDegradationController.AuditDegradationResponse.from(event);

        assertThat(response.reason()).isEqualTo("HEAD_MANIFEST_MALFORMED");
        assertThat(response.observedVersion()).isEqualTo("version-7");
        assertThat(response.observedDigest()).isEqualTo("digest-7");
        assertThat(response.recoveryAction()).isEqualTo("RECONSTRUCT_AND_CAS_HEAD");
        assertThat(response.recoveryOutcome()).isEqualTo("CAS_CONFLICT");
        assertThat(response.recoveryAttemptedAt()).isEqualTo(Instant.parse("2026-10-11T12:01:00Z"));
    }

    private ExternalAuditHeadForensicService service(AuditDegradationEventRepository repository) {
        return new ExternalAuditHeadForensicService(
                repository,
                mock(ExternalAuditHeadRecoveryTransitions.class)
        );
    }
}
