package com.frauddetection.alert.system.trustlevel.application;

import com.frauddetection.alert.audit.AuditDegradationEventDocument;
import com.frauddetection.alert.audit.AuditDegradationEventRepository;
import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.ExternalAuditHeadForensicService;
import com.frauddetection.alert.audit.ExternalAuditHeadRecoveryTransitions;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorCoverageResponse;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorSink;
import com.frauddetection.alert.audit.external.ExternalAuditIntegrityService;
import com.frauddetection.alert.audit.external.ExternalDurabilityGuarantee;
import com.frauddetection.alert.audit.external.ExternalImmutabilityLevel;
import com.frauddetection.alert.audit.external.ExternalWitnessCapabilities;
import com.frauddetection.alert.audit.external.ExternalWitnessTimestampType;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalHeadIntegrityTrustPostureIntegrationTest {

    @Test
    void malformedHeadIncidentSurvivesFallbackAndBlocksHealthyUntilVerifiedRepair() {
        AuditDegradationEventRepository repository = mock(AuditDegradationEventRepository.class);
        AtomicReference<AuditDegradationEventDocument> persisted = new AtomicReference<>();
        when(repository.insert(any(AuditDegradationEventDocument.class))).thenAnswer(invocation -> {
            AuditDegradationEventDocument event = invocation.getArgument(0);
            persisted.set(event);
            return event;
        });
        when(repository.countByTypeAndResolved(
                eq(AuditDegradationEventDocument.TYPE_EXTERNAL_HEAD_INTEGRITY),
                eq(false)
        )).thenAnswer(ignored -> persisted.get() != null && !persisted.get().isResolved() ? 1L : 0L);
        when(repository.save(any(AuditDegradationEventDocument.class))).thenAnswer(invocation -> {
            AuditDegradationEventDocument event = invocation.getArgument(0);
            persisted.set(event);
            return event;
        });

        ExternalAuditHeadRecoveryTransitions recoveryTransitions = mock(ExternalAuditHeadRecoveryTransitions.class);
        ExternalAuditHeadForensicService forensicService = new ExternalAuditHeadForensicService(
                repository,
                recoveryTransitions
        );
        String incidentId = forensicService.recordIncident(
                "source_service:alert-service",
                "malformed-version",
                "a".repeat(64),
                "HEAD_MANIFEST_MALFORMED",
                Instant.parse("2026-10-12T08:00:00Z")
        );
        when(repository.findByAuditId(incidentId)).thenAnswer(ignored -> Optional.ofNullable(persisted.get()));

        SystemTrustLevel degraded = trustService(repository).trustLevel();

        assertThat(persisted.get().isResolved()).isFalse();
        assertThat(degraded.coverageStatus()).isEqualTo("HEALTHY");
        assertThat(degraded.guaranteeLevel()).isEqualTo("FDP24_DEGRADED");
        assertThat(degraded.reasonCode()).isEqualTo("EXTERNAL_HEAD_INTEGRITY_UNRESOLVED");

        org.mockito.Mockito.doAnswer(invocation -> {
            persisted.get().setRecoveryOutcome("VERIFIED_REPAIRED");
            persisted.get().setRecoveryAttemptedAt(invocation.getArgument(1));
            persisted.get().setResolved(true);
            return null;
        }).when(recoveryTransitions).recordVerifiedRepair(
                org.mockito.ArgumentMatchers.anyString(),
                any(Instant.class)
        );
        forensicService.recordVerifiedRepair(
                incidentId,
                Instant.parse("2026-10-12T08:05:00Z")
        );
        SystemTrustLevelService restarted = trustService(repository);

        assertThat(restarted.trustLevel().guaranteeLevel()).isEqualTo("FDP24_HEALTHY");
        assertThat(restarted.trustLevel().guaranteeLevel()).isEqualTo("FDP24_HEALTHY");
    }

    private SystemTrustLevelService trustService(AuditDegradationEventRepository repository) {
        ExternalAuditIntegrityService integrityService = mock(ExternalAuditIntegrityService.class);
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        TransactionalOutboxRecordRepository outboxRepository = mock(TransactionalOutboxRecordRepository.class);
        when(integrityService.coverage("alert-service", 100)).thenReturn(new ExternalAuditAnchorCoverageResponse(
                "AVAILABLE",
                1,
                1,
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
        when(outboxRepository.countByStatus(any(TransactionalOutboxStatus.class))).thenReturn(0L);
        when(outboxRepository.findTopByStatusInOrderByCreatedAtAsc(any())).thenReturn(Optional.empty());
        return new SystemTrustLevelService(
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
                new AuditDegradationService(repository),
                outboxRepository,
                null,
                null
        );
    }
}
