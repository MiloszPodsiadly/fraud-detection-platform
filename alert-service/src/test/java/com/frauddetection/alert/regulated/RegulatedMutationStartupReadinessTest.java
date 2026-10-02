package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditEventRepository;
import com.frauddetection.alert.audit.external.AuditEventPublicationStatusLookup;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxRuntimeReadiness;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RegulatedMutationStartupReadinessTest {

    @Test
    void schedulersAreNoOpAfterContextRefreshButBeforeApplicationReady() {
        TransactionalOutboxRuntimeReadiness readiness = preflightPassedReadiness();
        MutationEvidenceConfirmationService evidenceService = mock(MutationEvidenceConfirmationService.class);
        RegulatedMutationRecoveryService recoveryService = mock(RegulatedMutationRecoveryService.class);

        new MutationEvidenceConfirmationScheduler(evidenceService, true, readiness).confirmPendingEvidence();
        new RegulatedMutationRecoveryScheduler(recoveryService, true, readiness).recoverStuckCommands();

        verifyNoInteractions(evidenceService, recoveryService);
    }

    @Test
    void disabledSchedulersRemainDisabledAfterApplicationReady() {
        TransactionalOutboxRuntimeReadiness readiness = readyReadiness();
        MutationEvidenceConfirmationService evidenceService = mock(MutationEvidenceConfirmationService.class);
        RegulatedMutationRecoveryService recoveryService = mock(RegulatedMutationRecoveryService.class);

        new MutationEvidenceConfirmationScheduler(evidenceService, false, readiness).confirmPendingEvidence();
        new RegulatedMutationRecoveryScheduler(recoveryService, false, readiness).recoverStuckCommands();

        verifyNoInteractions(evidenceService, recoveryService);
    }

    @Test
    void readySchedulersInvokeTheirServices() {
        TransactionalOutboxRuntimeReadiness readiness = readyReadiness();
        MutationEvidenceConfirmationService evidenceService = mock(MutationEvidenceConfirmationService.class);
        RegulatedMutationRecoveryService recoveryService = mock(RegulatedMutationRecoveryService.class);

        new MutationEvidenceConfirmationScheduler(evidenceService, true, readiness).confirmPendingEvidence();
        new RegulatedMutationRecoveryScheduler(recoveryService, true, readiness).recoverStuckCommands();

        verify(evidenceService).confirmPendingEvidence(100);
        verify(recoveryService).recoverStuckCommands();
    }

    @Test
    void controllerAndDirectMutationServicesRejectBeforeApplicationReady() {
        TransactionalOutboxRuntimeReadiness readiness = preflightPassedReadiness();
        RegulatedMutationRecoveryService mockedRecovery = mock(RegulatedMutationRecoveryService.class);
        RegulatedMutationRecoveryController controller = new RegulatedMutationRecoveryController(
                mockedRecovery,
                mock(RegulatedMutationInspectionRateLimiter.class),
                mock(SensitiveReadAuditService.class),
                readiness
        );
        RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        MutationEvidenceConfirmationService evidenceService = evidenceService(commandRepository, readiness);
        RegulatedMutationRecoveryService recoveryService = recoveryService(commandRepository, readiness);

        assertThatThrownBy(controller::recover).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> evidenceService.confirmPendingEvidence(100))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(recoveryService::recoverStuckCommands)
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(mockedRecovery, commandRepository);
    }

    @Test
    void coordinatorRejectsNewCommandCreationBeforeApplicationReady() {
        TransactionalOutboxRuntimeReadiness readiness = preflightPassedReadiness();
        RegulatedMutationCommandRepository repository = mock(RegulatedMutationCommandRepository.class);
        MongoRegulatedMutationCoordinator coordinator = new MongoRegulatedMutationCoordinator(
                repository,
                mock(RegulatedMutationExecutorRegistry.class),
                new RegulatedMutationConflictPolicy(),
                readiness
        );

        assertThatThrownBy(() -> coordinator.commit(null)).isInstanceOf(IllegalStateException.class);

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(repository);
    }

    private MutationEvidenceConfirmationService evidenceService(
            RegulatedMutationCommandRepository commandRepository,
            TransactionalOutboxRuntimeReadiness readiness
    ) {
        return new MutationEvidenceConfirmationService(
                commandRepository,
                mock(TransactionalOutboxRecordRepository.class),
                mock(AuditEventRepository.class),
                mock(AuditEventPublicationStatusLookup.class),
                mock(MongoTemplate.class),
                mock(AlertServiceMetrics.class),
                mock(RegulatedMutationFencedCommandWriter.class),
                mock(RegulatedMutationDurableLocalFinalizationProof.class),
                new RegulatedMutationTransactionRunner(RegulatedMutationTransactionMode.OFF, null),
                false,
                false,
                readiness
        );
    }

    private RegulatedMutationRecoveryService recoveryService(
            RegulatedMutationCommandRepository commandRepository,
            TransactionalOutboxRuntimeReadiness readiness
    ) {
        return new RegulatedMutationRecoveryService(
                commandRepository,
                mock(AlertServiceMetrics.class),
                List.of(),
                mock(RegulatedMutationFencedCommandWriter.class),
                mock(RegulatedMutationDurableLocalFinalizationProof.class),
                new RegulatedMutationPublicStatusMapper(),
                Duration.ofMinutes(2),
                readiness
        );
    }

    private TransactionalOutboxRuntimeReadiness preflightPassedReadiness() {
        TransactionalOutboxRuntimeReadiness readiness = new TransactionalOutboxRuntimeReadiness();
        readiness.markPreflightPassed();
        return readiness;
    }

    private TransactionalOutboxRuntimeReadiness readyReadiness() {
        TransactionalOutboxRuntimeReadiness readiness = preflightPassedReadiness();
        readiness.onApplicationEvent(null);
        return readiness;
    }
}
