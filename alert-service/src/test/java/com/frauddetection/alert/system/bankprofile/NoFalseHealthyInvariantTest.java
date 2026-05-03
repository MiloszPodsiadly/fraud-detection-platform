package com.frauddetection.alert.system.bankprofile;

import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorSink;
import com.frauddetection.alert.audit.external.ExternalAuditIntegrityService;
import com.frauddetection.alert.audit.external.ExternalDurabilityGuarantee;
import com.frauddetection.alert.audit.external.ExternalImmutabilityLevel;
import com.frauddetection.alert.audit.external.ExternalWitnessCapabilities;
import com.frauddetection.alert.audit.external.ExternalWitnessTimestampType;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorCoverageResponse;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorMissingRange;
import com.frauddetection.alert.fdp28.InvariantAssert;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.regulated.RegulatedMutationRecoveryService;
import com.frauddetection.alert.system.SystemTrustLevelController;
import com.frauddetection.alert.system.SystemTrustLevelResponse;
import com.frauddetection.alert.trust.TrustIncidentService;
import com.frauddetection.alert.trust.TrustIncidentSummary;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("failure-injection")
@Tag("invariant-proof")
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

    @Test
    void shouldNotReportHealthyWhenExternalCoverageIsUnavailable() {
        Fixture fixture = new Fixture();
        when(fixture.integrityService.coverage("alert-service", 100)).thenReturn(new ExternalAuditAnchorCoverageResponse(
                "UNAVAILABLE",
                10,
                0,
                10,
                null,
                List.of(),
                false,
                100,
                "HEAD_SCAN_PAGINATION_UNSUPPORTED",
                "External audit head cannot be proven."
        ));

        SystemTrustLevelResponse response = fixture.controller().trustLevel();

        InvariantAssert.noFalseHealthy(response);
        assertThat(response.coverageStatus()).isEqualTo("DEGRADED");
        assertThat(response.reasonCode()).isEqualTo("HEAD_SCAN_PAGINATION_UNSUPPORTED");
    }

    @Test
    void shouldNotReportHealthyWhenRequiredExternalWitnessUnavailable() {
        Fixture fixture = new Fixture();
        when(fixture.integrityService.coverage("alert-service", 100)).thenReturn(new ExternalAuditAnchorCoverageResponse(
                "UNAVAILABLE",
                10,
                0,
                10,
                null,
                List.of(),
                false,
                100,
                "EXTERNAL_WITNESS_UNAVAILABLE",
                "External witness is unavailable."
        ));

        SystemTrustLevelResponse response = fixture.controller().trustLevel();

        InvariantAssert.noFalseHealthy(response);
        assertThat(response.coverageStatus()).isEqualTo("DEGRADED");
        assertThat(response.reasonCode()).isEqualTo("EXTERNAL_WITNESS_UNAVAILABLE");
        assertThat(response.externalAnchorStrength()).isEqualTo("NONE");
        assertThat(response.guaranteeLevel()).isNotEqualTo("FDP24_HEALTHY");
    }

    @Test
    void shouldNotReportHealthyWhenExternalCoverageHasMissingRanges() {
        Fixture fixture = new Fixture();
        when(fixture.integrityService.coverage("alert-service", 100)).thenReturn(new ExternalAuditAnchorCoverageResponse(
                "AVAILABLE",
                10,
                10,
                0,
                null,
                List.of(new ExternalAuditAnchorMissingRange(4L, 4L)),
                false,
                100,
                "EXTERNAL_RANGE_MISSING",
                null
        ));

        SystemTrustLevelResponse response = fixture.controller().trustLevel();

        InvariantAssert.noFalseHealthy(response);
        assertThat(response.coverageStatus()).isEqualTo("DEGRADED");
        assertThat(response.missingRanges()).isEqualTo(1);
    }

    @Test
    void shouldNotReportHealthyWhenRequiredPublicationFailuresExist() {
        Fixture fixture = new Fixture();
        when(fixture.integrityService.coverage("alert-service", 100)).thenReturn(degradedPublicationCoverage(1, 0));

        SystemTrustLevelResponse response = fixture.controller().trustLevel();

        InvariantAssert.noFalseHealthy(response);
        assertThat(response.requiredPublicationFailures()).isEqualTo(1);
    }

    @Test
    void shouldNotReportHealthyWhenLocalStatusIsUnverified() {
        Fixture fixture = new Fixture();
        when(fixture.integrityService.coverage("alert-service", 100)).thenReturn(degradedPublicationCoverage(0, 1));

        SystemTrustLevelResponse response = fixture.controller().trustLevel();

        InvariantAssert.noFalseHealthy(response);
        assertThat(response.localStatusUnverified()).isEqualTo(1);
    }

    @Test
    void shouldNotReportHealthyWhenWitnessCapabilitiesAreOnlyDeclared() {
        Fixture fixture = new Fixture();
        when(fixture.sink.capabilities()).thenReturn(new ExternalWitnessCapabilities(
                "OBJECT_STORE",
                "object-store",
                "CROSS_ORG",
                ExternalImmutabilityLevel.CONFIGURED,
                true,
                true,
                false,
                false,
                ExternalWitnessTimestampType.APP_OBSERVED,
                "WEAK",
                false,
                false,
                false,
                ExternalDurabilityGuarantee.WITNESS_RETENTION
        ));

        SystemTrustLevelResponse response = fixture.controller().trustLevel();

        assertThat(response.guaranteeLevel()).isEqualTo("FDP24_DEGRADED");
        assertThat(response.witnessStatus()).isEqualTo("DECLARED_CAPABLE");
    }

    @Test
    void shouldNotReportHealthyWhenExternalSignatureStatePreventsValidIntegrity() {
        Fixture fixture = new Fixture();
        when(fixture.integrityService.coverage("alert-service", 100)).thenReturn(new ExternalAuditAnchorCoverageResponse(
                "AVAILABLE",
                10,
                9,
                1,
                null,
                List.of(),
                false,
                100,
                "SIGNATURE_UNAVAILABLE_REQUIRED",
                "Trust Authority signature unavailable."
        ));

        SystemTrustLevelResponse response = fixture.controller().trustLevel();

        InvariantAssert.noFalseHealthy(response);
        assertThat(response.reasonCode()).isEqualTo("SIGNATURE_UNAVAILABLE_REQUIRED");
    }

    @Test
    void shouldNotReportHealthyWhenOpenCriticalTrustIncidentExists() {
        Fixture fixture = new Fixture();
        when(fixture.trustIncidentService.summary()).thenReturn(new TrustIncidentSummary(
                1L,
                0L,
                0L,
                60L,
                List.of("OUTBOX_TERMINAL_FAILURE"),
                "CRITICAL"
        ));

        SystemTrustLevelResponse response = fixture.controller().trustLevel();

        InvariantAssert.noFalseHealthy(response);
        assertThat(response.reasonCode()).isEqualTo("TRUST_INCIDENT_OPEN_CRITICAL");
        assertThat(response.openCriticalIncidentCount()).isEqualTo(1L);
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

    private ExternalAuditAnchorCoverageResponse degradedPublicationCoverage(
            int requiredPublicationFailures,
            int localStatusUnverified
    ) {
        return new ExternalAuditAnchorCoverageResponse(
                "AVAILABLE",
                10,
                10,
                0,
                null,
                List.of(),
                false,
                100,
                null,
                null,
                "DEGRADED",
                0,
                null,
                50,
                requiredPublicationFailures > 0,
                requiredPublicationFailures,
                localStatusUnverified,
                0,
                requiredPublicationFailures + localStatusUnverified
        );
    }
}
