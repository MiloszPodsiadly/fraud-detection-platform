package com.frauddetection.alert.system.trustlevel.application;

import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorCoverageResponse;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorSink;
import com.frauddetection.alert.audit.external.ExternalAuditIntegrityService;
import com.frauddetection.alert.audit.external.ExternalDurabilityGuarantee;
import com.frauddetection.alert.audit.external.ExternalImmutabilityLevel;
import com.frauddetection.alert.audit.external.ExternalWitnessCapabilities;
import com.frauddetection.alert.audit.external.ExternalWitnessTimestampType;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.regulated.RegulatedMutationRecoveryService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemTrustLevelServiceTest {

    @Test
    void shouldExposeFailClosedSignedExternalTrustLevel() {
        ExternalAuditIntegrityService integrityService = mock(ExternalAuditIntegrityService.class);
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AuditDegradationService degradationService = mock(AuditDegradationService.class);
        TransactionalOutboxRecordRepository outboxRepository = healthyOutboxRepository();
        when(integrityService.coverage("alert-service", 100)).thenReturn(healthyCoverage());
        when(sink.capabilities()).thenReturn(verifiedCapabilities());
        when(degradationService.unresolvedPostCommitDegradedCount()).thenReturn(0L);
        SystemTrustLevelService service = service(
                true,
                true,
                true,
                true,
                true,
                true,
                Duration.ofMinutes(10),
                integrityService,
                sink,
                degradationService,
                outboxRepository,
                null
        );

        SystemTrustLevel response = service.trustLevel();

        assertThat(response.guaranteeLevel()).isEqualTo("FDP24_HEALTHY");
        assertThat(response.publicationEnabled()).isTrue();
        assertThat(response.publicationRequired()).isTrue();
        assertThat(response.failClosed()).isTrue();
        assertThat(response.externalAnchorStrength()).isEqualTo("SIGNED_EXTERNAL");
        assertThat(response.coverageStatus()).isEqualTo("HEALTHY");
        assertThat(response.witnessStatus()).isEqualTo("PROVIDER_CAPABILITY_VERIFIED");
        assertThat(response.transactionMode()).isEqualTo("REQUIRED");
        assertThat(response.transactionCapabilityStatus()).isEqualTo("LOCAL_MONGO_TRANSACTION_REQUIRED");
        assertThat(response.outboxDeliveryMode()).isEqualTo("TRANSACTIONAL_OUTBOX_AT_LEAST_ONCE");
        assertThat(response.evidenceConfirmationMode()).isEqualTo("ENABLED_PROVENANCE_AWARE");
        assertThat(response.evidenceConfirmationPendingCount()).isZero();
    }

    @Test
    void shouldNotMarketBestEffortAsHealthyFailClosedMode() {
        ExternalAuditIntegrityService integrityService = mock(ExternalAuditIntegrityService.class);
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        when(integrityService.coverage("alert-service", 100)).thenReturn(healthyCoverage());
        when(sink.capabilities()).thenReturn(verifiedCapabilities());
        SystemTrustLevelService service = service(
                true,
                false,
                false,
                false,
                false,
                false,
                Duration.ofMinutes(10),
                integrityService,
                sink,
                null,
                null,
                null
        );

        SystemTrustLevel response = service.trustLevel();

        assertThat(response.guaranteeLevel()).isEqualTo("BEST_EFFORT");
        assertThat(response.externalAnchorStrength()).isEqualTo("UNSIGNED_EXTERNAL");
        assertThat(response.transactionMode()).isEqualTo("OFF");
        assertThat(response.transactionCapabilityStatus()).isEqualTo("NON_TRANSACTIONAL_RECOVERABLE_SAGA");
    }

    @Test
    void shouldDowngradeFailClosedWhenCoverageIsDegraded() {
        ExternalAuditIntegrityService integrityService = mock(ExternalAuditIntegrityService.class);
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AuditDegradationService degradationService = mock(AuditDegradationService.class);
        when(integrityService.coverage("alert-service", 100)).thenReturn(new ExternalAuditAnchorCoverageResponse(
                "AVAILABLE",
                10,
                9,
                1,
                null,
                List.of(),
                false,
                100,
                null,
                null
        ));
        when(sink.capabilities()).thenReturn(verifiedCapabilities());
        when(degradationService.unresolvedPostCommitDegradedCount()).thenReturn(0L);
        SystemTrustLevelService service = new SystemTrustLevelService(
                true,
                true,
                true,
                true,
                true,
                integrityService,
                sink
        );

        SystemTrustLevel response = service.trustLevel();

        assertThat(response.guaranteeLevel()).isEqualTo("FDP24_DEGRADED");
        assertThat(response.externalAnchorStrength()).isEqualTo("NONE");
    }

    @Test
    void shouldReturnNoneWhenPublicationDisabled() {
        ExternalAuditIntegrityService integrityService = mock(ExternalAuditIntegrityService.class);
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        when(sink.capabilities()).thenReturn(new ExternalWitnessCapabilities(
                "DISABLED",
                "disabled",
                "NONE",
                ExternalImmutabilityLevel.NONE,
                false,
                false,
                false,
                false,
                ExternalWitnessTimestampType.APP_OBSERVED,
                "WEAK",
                false,
                false,
                false,
                ExternalDurabilityGuarantee.NONE
        ));
        SystemTrustLevelService service = new SystemTrustLevelService(
                false,
                false,
                false,
                false,
                false,
                integrityService,
                sink
        );

        SystemTrustLevel response = service.trustLevel();

        assertThat(response.guaranteeLevel()).isEqualTo("NONE");
        assertThat(response.externalAnchorStrength()).isEqualTo("NONE");
    }

    @Test
    void shouldDowngradeFailClosedWhenPostCommitAuditDegradedWasObserved() {
        ExternalAuditIntegrityService integrityService = mock(ExternalAuditIntegrityService.class);
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AuditDegradationService degradationService = mock(AuditDegradationService.class);
        when(integrityService.coverage("alert-service", 100)).thenReturn(healthyCoverage());
        when(sink.capabilities()).thenReturn(verifiedCapabilities());
        when(degradationService.unresolvedPostCommitDegradedCount()).thenReturn(1L);
        SystemTrustLevelService service = new SystemTrustLevelService(
                true,
                true,
                true,
                true,
                true,
                true,
                Duration.ofMinutes(10),
                integrityService,
                sink,
                degradationService,
                null,
                null
        );

        SystemTrustLevel response = service.trustLevel();

        assertThat(response.guaranteeLevel()).isEqualTo("FDP24_DEGRADED");
        assertThat(response.postCommitAuditDegraded()).isEqualTo(1L);
        assertThat(response.unresolvedDegradationCount()).isEqualTo(1L);
        assertThat(response.guaranteeLevel()).isNotEqualTo("FDP24_HEALTHY");
    }

    @Test
    void shouldDowngradeFailClosedWhenOutboxHasTerminalFailure() {
        ExternalAuditIntegrityService integrityService = mock(ExternalAuditIntegrityService.class);
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AuditDegradationService degradationService = mock(AuditDegradationService.class);
        TransactionalOutboxRecordRepository outboxRepository = healthyOutboxRepository();
        when(integrityService.coverage("alert-service", 100)).thenReturn(healthyCoverage());
        when(sink.capabilities()).thenReturn(verifiedCapabilities());
        when(degradationService.unresolvedPostCommitDegradedCount()).thenReturn(0L);
        when(outboxRepository.countByStatus(TransactionalOutboxStatus.FAILED_TERMINAL)).thenReturn(1L);
        SystemTrustLevelService service = service(
                true,
                true,
                true,
                true,
                true,
                true,
                Duration.ofMinutes(10),
                integrityService,
                sink,
                degradationService,
                outboxRepository,
                null
        );

        SystemTrustLevel response = service.trustLevel();

        assertThat(response.guaranteeLevel()).isEqualTo("FDP24_DEGRADED");
        assertThat(response.outboxFailedTerminalCount()).isEqualTo(1L);
        assertThat(response.reasonCode()).isEqualTo("OUTBOX_TERMINAL_FAILURE");
    }

    @Test
    void shouldDowngradeFailClosedWhenRegulatedMutationRecoveryIsRequired() {
        ExternalAuditIntegrityService integrityService = mock(ExternalAuditIntegrityService.class);
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AuditDegradationService degradationService = mock(AuditDegradationService.class);
        TransactionalOutboxRecordRepository outboxRepository = healthyOutboxRepository();
        RegulatedMutationRecoveryService recoveryService = mock(RegulatedMutationRecoveryService.class);
        when(integrityService.coverage("alert-service", 100)).thenReturn(healthyCoverage());
        when(sink.capabilities()).thenReturn(verifiedCapabilities());
        when(degradationService.unresolvedPostCommitDegradedCount()).thenReturn(0L);
        when(recoveryService.recoveryRequiredCount()).thenReturn(1L);
        SystemTrustLevelService service = service(
                true,
                true,
                true,
                true,
                true,
                true,
                Duration.ofMinutes(10),
                integrityService,
                sink,
                degradationService,
                outboxRepository,
                recoveryService
        );

        SystemTrustLevel response = service.trustLevel();

        assertThat(response.guaranteeLevel()).isEqualTo("FDP24_DEGRADED");
        assertThat(response.regulatedMutationRecoveryRequiredCount()).isEqualTo(1L);
        assertThat(response.reasonCode()).isEqualTo("REGULATED_MUTATION_RECOVERY_REQUIRED");
    }

    @Test
    void shouldDowngradeFailClosedWhenRegulatedMutationHealthSignalsAreNonZero() {
        ExternalAuditIntegrityService integrityService = mock(ExternalAuditIntegrityService.class);
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AuditDegradationService degradationService = mock(AuditDegradationService.class);
        TransactionalOutboxRecordRepository outboxRepository = healthyOutboxRepository();
        RegulatedMutationRecoveryService recoveryService = mock(RegulatedMutationRecoveryService.class);
        when(integrityService.coverage("alert-service", 100)).thenReturn(healthyCoverage());
        when(sink.capabilities()).thenReturn(verifiedCapabilities());
        when(degradationService.unresolvedPostCommitDegradedCount()).thenReturn(0L);
        when(recoveryService.staleProcessingLeaseCount()).thenReturn(1L);
        when(recoveryService.finalizeRecoveryRequiredCount()).thenReturn(2L);
        when(recoveryService.evidenceConfirmationPendingCount()).thenReturn(4L);
        when(recoveryService.repeatedRecoveryFailureCount()).thenReturn(3L);
        when(recoveryService.oldestRecoveryRequiredAgeSeconds()).thenReturn(120L);
        SystemTrustLevelService service = service(
                true,
                true,
                true,
                true,
                true,
                true,
                Duration.ofMinutes(10),
                integrityService,
                sink,
                degradationService,
                outboxRepository,
                recoveryService
        );

        SystemTrustLevel response = service.trustLevel();

        assertThat(response.guaranteeLevel()).isEqualTo("FDP24_DEGRADED");
        assertThat(response.staleProcessingLeaseCount()).isEqualTo(1L);
        assertThat(response.finalizeRecoveryRequiredCount()).isEqualTo(2L);
        assertThat(response.evidenceConfirmationPendingCount()).isEqualTo(4L);
        assertThat(response.repeatedRecoveryFailureCount()).isEqualTo(3L);
        assertThat(response.oldestRecoveryRequiredAgeSeconds()).isEqualTo(120L);
        assertThat(response.reasonCode()).isEqualTo("REGULATED_MUTATION_STALE_PROCESSING_LEASE");
    }

    @Test
    void shouldNotReportHealthyWhenBankModeLacksExternalAnchoring() {
        ExternalAuditIntegrityService integrityService = mock(ExternalAuditIntegrityService.class);
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        TransactionalOutboxRecordRepository outboxRepository = healthyOutboxRepository();
        when(integrityService.coverage("alert-service", 100)).thenReturn(healthyCoverage());
        when(sink.capabilities()).thenReturn(verifiedCapabilities());
        SystemTrustLevelService service = service(
                false,
                false,
                false,
                true,
                true,
                true,
                Duration.ofMinutes(10),
                integrityService,
                sink,
                null,
                outboxRepository,
                null
        );

        SystemTrustLevel response = service.trustLevel();

        assertThat(response.guaranteeLevel()).isNotEqualTo("FDP24_HEALTHY");
        assertThat(response.reasonCode()).isEqualTo("EXTERNAL_ANCHORING_REQUIRED_IN_BANK_MODE");
    }

    @Test
    void shouldNotReportHealthyWhenBankModeLacksTrustAuthoritySigning() {
        ExternalAuditIntegrityService integrityService = mock(ExternalAuditIntegrityService.class);
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        TransactionalOutboxRecordRepository outboxRepository = healthyOutboxRepository();
        when(integrityService.coverage("alert-service", 100)).thenReturn(healthyCoverage());
        when(sink.capabilities()).thenReturn(verifiedCapabilities());
        SystemTrustLevelService service = service(
                true,
                true,
                true,
                true,
                false,
                false,
                Duration.ofMinutes(10),
                integrityService,
                sink,
                null,
                outboxRepository,
                null
        );

        SystemTrustLevel response = service.trustLevel();

        assertThat(response.guaranteeLevel()).isNotEqualTo("FDP24_HEALTHY");
        assertThat(response.reasonCode()).isEqualTo("TRUST_AUTHORITY_SIGNING_REQUIRED_IN_BANK_MODE");
    }

    private SystemTrustLevelService service(
            boolean publicationEnabled,
            boolean publicationRequired,
            boolean failClosed,
            boolean bankModeFailClosed,
            boolean trustAuthorityEnabled,
            boolean signingRequired,
            Duration staleOutboxThreshold,
            ExternalAuditIntegrityService integrityService,
            ExternalAuditAnchorSink sink,
            AuditDegradationService degradationService,
            TransactionalOutboxRecordRepository outboxRepository,
            RegulatedMutationRecoveryService recoveryService
    ) {
        return new SystemTrustLevelService(
                publicationEnabled,
                publicationRequired,
                failClosed,
                bankModeFailClosed,
                trustAuthorityEnabled,
                signingRequired,
                staleOutboxThreshold,
                bankModeFailClosed ? "REQUIRED" : "OFF",
                true,
                true,
                integrityService,
                sink,
                degradationService,
                outboxRepository,
                recoveryService,
                null
        );
    }

    private TransactionalOutboxRecordRepository healthyOutboxRepository() {
        TransactionalOutboxRecordRepository repository = mock(TransactionalOutboxRecordRepository.class);
        when(repository.countByStatus(any(TransactionalOutboxStatus.class))).thenReturn(0L);
        when(repository.findTopByStatusInOrderByCreatedAtAsc(any())).thenReturn(Optional.empty());
        return repository;
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
