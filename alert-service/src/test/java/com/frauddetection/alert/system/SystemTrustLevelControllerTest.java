package com.frauddetection.alert.system;

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
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.regulated.RegulatedMutationRecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemTrustLevelControllerTest {

    @Test
    void shouldExposeFailClosedSignedExternalTrustLevel() {
        ExternalAuditIntegrityService integrityService = mock(ExternalAuditIntegrityService.class);
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AuditDegradationService degradationService = mock(AuditDegradationService.class);
        AlertRepository alertRepository = mock(AlertRepository.class);
        TransactionalOutboxRecordRepository outboxRepository = healthyOutboxRepository();
        when(integrityService.coverage("alert-service", 100)).thenReturn(healthyCoverage());
        when(sink.capabilities()).thenReturn(verifiedCapabilities());
        when(degradationService.unresolvedPostCommitDegradedCount()).thenReturn(0L);
        SystemTrustLevelController controller = controller(
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
                alertRepository,
                outboxRepository,
                null
        );

        SystemTrustLevelResponse response = controller.trustLevel();

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
        AuditDegradationService degradationService = mock(AuditDegradationService.class);
        AlertRepository alertRepository = mock(AlertRepository.class);
        when(integrityService.coverage("alert-service", 100)).thenReturn(healthyCoverage());
        when(sink.capabilities()).thenReturn(verifiedCapabilities());
        when(degradationService.unresolvedPostCommitDegradedCount()).thenReturn(0L);
        SystemTrustLevelController controller = new SystemTrustLevelController(
                true,
                false,
                false,
                false,
                false,
                false,
                integrityService,
                sink,
                null
        );

        SystemTrustLevelResponse response = controller.trustLevel();

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
        AlertRepository alertRepository = mock(AlertRepository.class);
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
        SystemTrustLevelController controller = new SystemTrustLevelController(
                true,
                true,
                true,
                true,
                true,
                integrityService,
                sink,
                null
        );

        SystemTrustLevelResponse response = controller.trustLevel();

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
        SystemTrustLevelController controller = new SystemTrustLevelController(
                false,
                false,
                false,
                false,
                false,
                integrityService,
                sink,
                null
        );

        SystemTrustLevelResponse response = controller.trustLevel();

        assertThat(response.guaranteeLevel()).isEqualTo("NONE");
        assertThat(response.externalAnchorStrength()).isEqualTo("NONE");
    }

    @Test
    void shouldDowngradeFailClosedWhenPostCommitAuditDegradedWasObserved() {
        ExternalAuditIntegrityService integrityService = mock(ExternalAuditIntegrityService.class);
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AuditDegradationService degradationService = mock(AuditDegradationService.class);
        AlertRepository alertRepository = mock(AlertRepository.class);
        when(integrityService.coverage("alert-service", 100)).thenReturn(healthyCoverage());
        when(sink.capabilities()).thenReturn(verifiedCapabilities());
        when(degradationService.unresolvedPostCommitDegradedCount()).thenReturn(1L);
        SystemTrustLevelController controller = new SystemTrustLevelController(
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
                alertRepository
        );

        SystemTrustLevelResponse response = controller.trustLevel();

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
        AlertRepository alertRepository = mock(AlertRepository.class);
        TransactionalOutboxRecordRepository outboxRepository = healthyOutboxRepository();
        when(integrityService.coverage("alert-service", 100)).thenReturn(healthyCoverage());
        when(sink.capabilities()).thenReturn(verifiedCapabilities());
        when(degradationService.unresolvedPostCommitDegradedCount()).thenReturn(0L);
        when(outboxRepository.countByStatus(TransactionalOutboxStatus.FAILED_TERMINAL)).thenReturn(1L);
        SystemTrustLevelController controller = controller(
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
                alertRepository,
                outboxRepository,
                null
        );

        SystemTrustLevelResponse response = controller.trustLevel();

        assertThat(response.guaranteeLevel()).isEqualTo("FDP24_DEGRADED");
        assertThat(response.outboxFailedTerminalCount()).isEqualTo(1L);
        assertThat(response.reasonCode()).isEqualTo("OUTBOX_TERMINAL_FAILURE");
    }

    @Test
    void shouldDowngradeFailClosedWhenRegulatedMutationRecoveryIsRequired() {
        ExternalAuditIntegrityService integrityService = mock(ExternalAuditIntegrityService.class);
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AuditDegradationService degradationService = mock(AuditDegradationService.class);
        AlertRepository alertRepository = mock(AlertRepository.class);
        TransactionalOutboxRecordRepository outboxRepository = healthyOutboxRepository();
        RegulatedMutationRecoveryService recoveryService = mock(RegulatedMutationRecoveryService.class);
        when(integrityService.coverage("alert-service", 100)).thenReturn(healthyCoverage());
        when(sink.capabilities()).thenReturn(verifiedCapabilities());
        when(degradationService.unresolvedPostCommitDegradedCount()).thenReturn(0L);
        when(recoveryService.recoveryRequiredCount()).thenReturn(1L);
        SystemTrustLevelController controller = controller(
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
                alertRepository,
                outboxRepository,
                recoveryService
        );

        SystemTrustLevelResponse response = controller.trustLevel();

        assertThat(response.guaranteeLevel()).isEqualTo("FDP24_DEGRADED");
        assertThat(response.regulatedMutationRecoveryRequiredCount()).isEqualTo(1L);
        assertThat(response.reasonCode()).isEqualTo("REGULATED_MUTATION_RECOVERY_REQUIRED");
    }

    @Test
    void shouldDowngradeFailClosedWhenRegulatedMutationHealthSignalsAreNonZero() {
        ExternalAuditIntegrityService integrityService = mock(ExternalAuditIntegrityService.class);
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        AuditDegradationService degradationService = mock(AuditDegradationService.class);
        AlertRepository alertRepository = mock(AlertRepository.class);
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
        SystemTrustLevelController controller = controller(
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
                alertRepository,
                outboxRepository,
                recoveryService
        );

        SystemTrustLevelResponse response = controller.trustLevel();

        assertThat(response.guaranteeLevel()).isEqualTo("FDP24_DEGRADED");
        assertThat(response.staleProcessingLeaseCount()).isEqualTo(1L);
        assertThat(response.finalizeRecoveryRequiredCount()).isEqualTo(2L);
        assertThat(response.evidenceConfirmationPendingCount()).isEqualTo(4L);
        assertThat(response.repeatedRecoveryFailureCount()).isEqualTo(3L);
        assertThat(response.oldestRecoveryRequiredAgeSeconds()).isEqualTo(120L);
        assertThat(response.reasonCode()).isEqualTo("REGULATED_MUTATION_STALE_PROCESSING_LEASE");
    }

    @Test
    void shouldFailStartupWhenRequiredFailClosedPublicationDoesNotEnableBankMode() {
        SystemTrustLevelController controller = new SystemTrustLevelController(
                true,
                true,
                true,
                false,
                false,
                false,
                Duration.ofMinutes(10),
                mock(ExternalAuditIntegrityService.class),
                mock(ExternalAuditAnchorSink.class),
                null,
                null
        );

        assertThatThrownBy(() -> controller.run(mock(org.springframework.boot.ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.audit.bank-mode.fail-closed=true is required");
    }

    @Test
    void shouldNotReportHealthyWhenBankModeLacksExternalAnchoring() {
        ExternalAuditIntegrityService integrityService = mock(ExternalAuditIntegrityService.class);
        ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
        TransactionalOutboxRecordRepository outboxRepository = healthyOutboxRepository();
        when(integrityService.coverage("alert-service", 100)).thenReturn(healthyCoverage());
        when(sink.capabilities()).thenReturn(verifiedCapabilities());
        SystemTrustLevelController controller = controller(
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
                null,
                outboxRepository,
                null
        );

        SystemTrustLevelResponse response = controller.trustLevel();

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
        SystemTrustLevelController controller = controller(
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
                null,
                outboxRepository,
                null
        );

        SystemTrustLevelResponse response = controller.trustLevel();

        assertThat(response.guaranteeLevel()).isNotEqualTo("FDP24_HEALTHY");
        assertThat(response.reasonCode()).isEqualTo("TRUST_AUTHORITY_SIGNING_REQUIRED_IN_BANK_MODE");
    }

    private SystemTrustLevelController controller(
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
            AlertRepository alertRepository,
            TransactionalOutboxRecordRepository outboxRepository,
            RegulatedMutationRecoveryService recoveryService
    ) {
        return new SystemTrustLevelController(
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
                alertRepository,
                provider(outboxRepository),
                recoveryService,
                provider(null),
                provider(null),
                provider(null)
        );
    }

    private TransactionalOutboxRecordRepository healthyOutboxRepository() {
        TransactionalOutboxRecordRepository repository = mock(TransactionalOutboxRecordRepository.class);
        when(repository.countByStatus(any(TransactionalOutboxStatus.class))).thenReturn(0L);
        when(repository.findTopByStatusInOrderByCreatedAtAsc(any())).thenReturn(Optional.empty());
        return repository;
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
