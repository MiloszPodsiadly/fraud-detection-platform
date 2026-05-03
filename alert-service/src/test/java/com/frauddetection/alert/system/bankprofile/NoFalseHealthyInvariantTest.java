package com.frauddetection.alert.system.bankprofile;

import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorSink;
import com.frauddetection.alert.audit.external.ExternalAuditIntegrityService;
import com.frauddetection.alert.audit.external.ExternalDurabilityGuarantee;
import com.frauddetection.alert.audit.external.ExternalImmutabilityLevel;
import com.frauddetection.alert.audit.external.ExternalWitnessCapabilities;
import com.frauddetection.alert.audit.external.ExternalWitnessTimestampType;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorCoverageResponse;
import com.frauddetection.alert.fdp28.InvariantAssert;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.regulated.RegulatedMutationRecoveryService;
import com.frauddetection.alert.system.SystemTrustLevelController;
import com.frauddetection.alert.system.SystemTrustLevelResponse;
import com.frauddetection.alert.trust.TrustIncidentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NoFalseHealthyInvariantTest {

    @Test
    void shouldNotReportHealthyWhenTransactionalOutboxStatusIsUnavailable() {
        Fixture fixture = new Fixture();
        when(fixture.outboxRepository.countByStatus(any(TransactionalOutboxStatus.class)))
                .thenThrow(new DataAccessResourceFailureException("mongo unavailable"));

        SystemTrustLevelResponse response = fixture.controller().trustLevel();

        InvariantAssert.noFalseHealthy(response);
        assertThat(response.reasonCode()).isEqualTo("OUTBOX_STATUS_UNAVAILABLE");
        assertThat(response.outboxFailedTerminalCount()).isEqualTo(1L);
        assertThat(response.outboxConfirmationUnknownCount()).isEqualTo(1L);
    }

    @Test
    void shouldNotReportHealthyWhenTrustIncidentControlPlaneIsUnavailable() {
        Fixture fixture = new Fixture();
        when(fixture.trustIncidentService.summary()).thenThrow(new IllegalStateException("repository unavailable"));

        SystemTrustLevelResponse response = fixture.controller().trustLevel();

        InvariantAssert.noFalseHealthy(response);
        assertThat(response.reasonCode()).isEqualTo("TRUST_INCIDENT_UNACKNOWLEDGED_CRITICAL");
        assertThat(response.incidentHealthStatus()).isEqualTo("CRITICAL");
    }

    private static final class Fixture {
        private final ExternalAuditIntegrityService integrityService = mock(ExternalAuditIntegrityService.class);
        private final ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        private final AuditDegradationService degradationService = mock(AuditDegradationService.class);
        private final AlertRepository alertRepository = mock(AlertRepository.class);
        private final TransactionalOutboxRecordRepository outboxRepository = mock(TransactionalOutboxRecordRepository.class);
        private final RegulatedMutationRecoveryService recoveryService = mock(RegulatedMutationRecoveryService.class);
        private final TrustIncidentService trustIncidentService = mock(TrustIncidentService.class);

        private Fixture() {
            when(integrityService.coverage("alert-service", 100)).thenReturn(healthyCoverage());
            when(sink.capabilities()).thenReturn(verifiedCapabilities());
            when(degradationService.unresolvedPostCommitDegradedCount()).thenReturn(0L);
            when(degradationService.pendingResolutionCount()).thenReturn(0L);
            when(degradationService.resolvedCount()).thenReturn(0L);
            when(outboxRepository.countByStatus(any(TransactionalOutboxStatus.class))).thenReturn(0L);
            when(outboxRepository.countByProjectionMismatchTrue()).thenReturn(0L);
            when(outboxRepository.findTopByStatusInOrderByCreatedAtAsc(any())).thenReturn(java.util.Optional.empty());
            when(trustIncidentService.summary()).thenReturn(com.frauddetection.alert.trust.TrustIncidentSummary.empty());
        }

        private SystemTrustLevelController controller() {
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
                    provider(null)
            );
        }

        @SuppressWarnings("unchecked")
        private <T> ObjectProvider<T> provider(T value) {
            ObjectProvider<T> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(value);
            return provider;
        }

        private ExternalAuditAnchorCoverageResponse healthyCoverage() {
            return new ExternalAuditAnchorCoverageResponse(
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
            );
        }

        private ExternalWitnessCapabilities verifiedCapabilities() {
            return new ExternalWitnessCapabilities(
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
            );
        }
    }
}
