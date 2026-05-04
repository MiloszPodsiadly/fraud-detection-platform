package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditChainIndexInitializer;
import com.frauddetection.alert.audit.LocalAuditPhaseWriterProperties;
import com.frauddetection.alert.audit.RegulatedMutationLocalAuditPhaseWriter;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidenceGatedFinalizeStartupGuardTest {

    @Test
    void shouldRejectSubmitDecisionEvidenceGatedFinalizeWhenTransactionsAreOff() {
        EvidenceGatedFinalizeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.OFF,
                true,
                true,
                true,
                true,
                mock(PlatformTransactionManager.class),
                mock(RegulatedMutationTransactionCapabilityProbe.class),
                mock(TransactionalOutboxRecordRepository.class),
                List.of(mockSubmitDecisionRecovery())
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.regulated-mutations.transaction-mode");
    }

    @Test
    void shouldIgnoreSubmitDecisionFlagWhenGlobalFlagIsDisabled() {
        EvidenceGatedFinalizeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.OFF,
                false,
                true,
                true,
                true,
                null,
                null,
                null,
                List.of()
        );

        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
    }

    @Test
    void shouldIgnoreGlobalFlagWhenSubmitDecisionFlagIsDisabled() {
        EvidenceGatedFinalizeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.OFF,
                true,
                false,
                true,
                true,
                null,
                null,
                null,
                List.of()
        );

        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
    }

    @Test
    void shouldAllowSubmitDecisionEvidenceGatedFinalizeWithCompleteRequiredConfig() {
        RegulatedMutationTransactionCapabilityProbe probe = mock(RegulatedMutationTransactionCapabilityProbe.class);
        EvidenceGatedFinalizeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.REQUIRED,
                true,
                true,
                true,
                true,
                mock(PlatformTransactionManager.class),
                probe,
                mock(TransactionalOutboxRecordRepository.class),
                List.of(mockSubmitDecisionRecovery())
        );

        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
        verify(probe).verify();
    }

    @Test
    void shouldRejectSubmitDecisionEvidenceGatedFinalizeWhenLocalAuditWriterIsMissing() {
        EvidenceGatedFinalizeStartupGuard guard = guardWithAuditWriterConfig(
                RegulatedMutationTransactionMode.REQUIRED,
                true,
                true,
                true,
                true,
                mock(PlatformTransactionManager.class),
                mock(RegulatedMutationTransactionCapabilityProbe.class),
                mock(TransactionalOutboxRecordRepository.class),
                null,
                validLocalAuditPhaseWriterProperties(),
                mockAuditChainIndexInitializer(true),
                List.of(mockSubmitDecisionRecovery())
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RegulatedMutationLocalAuditPhaseWriter");
    }

    @Test
    void shouldRejectSubmitDecisionEvidenceGatedFinalizeWhenLocalAuditWriterRetryConfigIsInvalid() {
        LocalAuditPhaseWriterProperties properties = validLocalAuditPhaseWriterProperties();
        properties.setMaxAppendAttempts(0);
        EvidenceGatedFinalizeStartupGuard guard = guardWithAuditWriterConfig(
                RegulatedMutationTransactionMode.REQUIRED,
                true,
                true,
                true,
                true,
                mock(PlatformTransactionManager.class),
                mock(RegulatedMutationTransactionCapabilityProbe.class),
                mock(TransactionalOutboxRecordRepository.class),
                mock(RegulatedMutationLocalAuditPhaseWriter.class),
                properties,
                mockAuditChainIndexInitializer(true),
                List.of(mockSubmitDecisionRecovery())
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.audit.local-phase-writer");
    }

    @Test
    void shouldRejectSubmitDecisionEvidenceGatedFinalizeWhenAuditChainIndexInitializerIsMissing() {
        EvidenceGatedFinalizeStartupGuard guard = guardWithAuditWriterConfig(
                RegulatedMutationTransactionMode.REQUIRED,
                true,
                true,
                true,
                true,
                mock(PlatformTransactionManager.class),
                mock(RegulatedMutationTransactionCapabilityProbe.class),
                mock(TransactionalOutboxRecordRepository.class),
                mock(RegulatedMutationLocalAuditPhaseWriter.class),
                validLocalAuditPhaseWriterProperties(),
                null,
                List.of(mockSubmitDecisionRecovery())
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AuditChainIndexInitializer");
    }

    @Test
    void shouldRejectSubmitDecisionEvidenceGatedFinalizeWhenAuditChainUniqueIndexesAreMissing() {
        EvidenceGatedFinalizeStartupGuard guard = guardWithAuditWriterConfig(
                RegulatedMutationTransactionMode.REQUIRED,
                true,
                true,
                true,
                true,
                mock(PlatformTransactionManager.class),
                mock(RegulatedMutationTransactionCapabilityProbe.class),
                mock(TransactionalOutboxRecordRepository.class),
                mock(RegulatedMutationLocalAuditPhaseWriter.class),
                validLocalAuditPhaseWriterProperties(),
                mockAuditChainIndexInitializer(false),
                List.of(mockSubmitDecisionRecovery())
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit chain unique indexes");
    }

    @Test
    void shouldIgnoreLocalAuditWriterGuardWhenEvidenceGatedFinalizeIsInactive() {
        LocalAuditPhaseWriterProperties properties = validLocalAuditPhaseWriterProperties();
        properties.setMaxTotalWaitMs(10_000);
        EvidenceGatedFinalizeStartupGuard guard = guardWithAuditWriterConfig(
                RegulatedMutationTransactionMode.OFF,
                false,
                true,
                true,
                true,
                null,
                null,
                null,
                null,
                properties,
                null,
                List.of()
        );

        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectSubmitDecisionEvidenceGatedFinalizeWhenOutboxRecoveryDisabled() {
        EvidenceGatedFinalizeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.REQUIRED,
                true,
                true,
                true,
                false,
                mock(PlatformTransactionManager.class),
                mock(RegulatedMutationTransactionCapabilityProbe.class),
                mock(TransactionalOutboxRecordRepository.class),
                List.of(mockSubmitDecisionRecovery())
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.outbox.recovery.enabled");
    }

    @Test
    void shouldRejectSubmitDecisionEvidenceGatedFinalizeWhenTransactionManagerIsMissing() {
        EvidenceGatedFinalizeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.REQUIRED,
                true,
                true,
                true,
                true,
                null,
                mock(RegulatedMutationTransactionCapabilityProbe.class),
                mock(TransactionalOutboxRecordRepository.class),
                List.of(mockSubmitDecisionRecovery())
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mongo transaction manager");
    }

    @Test
    void shouldRejectSubmitDecisionEvidenceGatedFinalizeWhenProbeIsDisabled() {
        EvidenceGatedFinalizeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.REQUIRED,
                true,
                true,
                false,
                true,
                mock(PlatformTransactionManager.class),
                mock(RegulatedMutationTransactionCapabilityProbe.class),
                mock(TransactionalOutboxRecordRepository.class),
                List.of(mockSubmitDecisionRecovery())
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.regulated-mutations.transaction-capability-probe.enabled");
    }

    @Test
    void shouldRejectSubmitDecisionEvidenceGatedFinalizeWhenProbeBeanIsMissing() {
        EvidenceGatedFinalizeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.REQUIRED,
                true,
                true,
                true,
                true,
                mock(PlatformTransactionManager.class),
                null,
                mock(TransactionalOutboxRecordRepository.class),
                List.of(mockSubmitDecisionRecovery())
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction capability probe");
    }

    @Test
    void shouldRejectSubmitDecisionEvidenceGatedFinalizeWhenProbeThrows() {
        RegulatedMutationTransactionCapabilityProbe probe = mock(RegulatedMutationTransactionCapabilityProbe.class);
        doThrow(new IllegalStateException("probe failed")).when(probe).verify();
        EvidenceGatedFinalizeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.REQUIRED,
                true,
                true,
                true,
                true,
                mock(PlatformTransactionManager.class),
                probe,
                mock(TransactionalOutboxRecordRepository.class),
                List.of(mockSubmitDecisionRecovery())
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("probe failed");
    }

    @Test
    void shouldRejectSubmitDecisionEvidenceGatedFinalizeWhenOutboxRepositoryIsMissing() {
        EvidenceGatedFinalizeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.REQUIRED,
                true,
                true,
                true,
                true,
                mock(PlatformTransactionManager.class),
                mock(RegulatedMutationTransactionCapabilityProbe.class),
                null,
                List.of(mockSubmitDecisionRecovery())
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TransactionalOutboxRecordRepository");
    }

    @Test
    void shouldRejectSubmitDecisionEvidenceGatedFinalizeWhenRecoveryStrategyIsMissing() {
        EvidenceGatedFinalizeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.REQUIRED,
                true,
                true,
                true,
                true,
                mock(PlatformTransactionManager.class),
                mock(RegulatedMutationTransactionCapabilityProbe.class),
                mock(TransactionalOutboxRecordRepository.class),
                List.of()
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("submit-decision recovery strategy");
    }

    @Test
    void shouldRejectUnsupportedFraudCaseEvidenceGatedFinalizeFlag() {
        EvidenceGatedFinalizeStartupGuard guard = guardWithUnsupportedMutation(true, false, false);

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported-mutation");
    }

    @Test
    void shouldRejectUnsupportedTrustIncidentEvidenceGatedFinalizeFlag() {
        EvidenceGatedFinalizeStartupGuard guard = guardWithUnsupportedMutation(false, true, false);

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported-mutation");
    }

    @Test
    void shouldRejectUnsupportedOutboxResolutionEvidenceGatedFinalizeFlag() {
        EvidenceGatedFinalizeStartupGuard guard = guardWithUnsupportedMutation(false, false, true);

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported-mutation");
    }

    @SuppressWarnings("unchecked")
    private EvidenceGatedFinalizeStartupGuard guard(
            RegulatedMutationTransactionMode mode,
            boolean globalEnabled,
            boolean submitDecisionEnabled,
            boolean probeEnabled,
            boolean outboxRecoveryEnabled,
            PlatformTransactionManager transactionManager,
            RegulatedMutationTransactionCapabilityProbe probe,
            TransactionalOutboxRecordRepository outboxRepository,
            List<RegulatedMutationRecoveryStrategy> recoveryStrategies
    ) {
        ObjectProvider<PlatformTransactionManager> transactionManagerProvider = mock(ObjectProvider.class);
        when(transactionManagerProvider.getIfAvailable()).thenReturn(transactionManager);
        ObjectProvider<RegulatedMutationTransactionCapabilityProbe> probeProvider = mock(ObjectProvider.class);
        when(probeProvider.getIfAvailable()).thenReturn(probe);
        ObjectProvider<TransactionalOutboxRecordRepository> outboxRepositoryProvider = mock(ObjectProvider.class);
        when(outboxRepositoryProvider.getIfAvailable()).thenReturn(outboxRepository);
        RegulatedMutationLocalAuditPhaseWriter localAuditPhaseWriter = mock(RegulatedMutationLocalAuditPhaseWriter.class);
        ObjectProvider<RegulatedMutationLocalAuditPhaseWriter> localAuditPhaseWriterProvider = mock(ObjectProvider.class);
        when(localAuditPhaseWriterProvider.getIfAvailable()).thenReturn(localAuditPhaseWriter);
        AuditChainIndexInitializer auditChainIndexInitializer = mockAuditChainIndexInitializer(true);
        ObjectProvider<AuditChainIndexInitializer> auditChainIndexInitializerProvider = mock(ObjectProvider.class);
        when(auditChainIndexInitializerProvider.getIfAvailable()).thenReturn(auditChainIndexInitializer);
        RegulatedMutationTransactionRunner runner = new RegulatedMutationTransactionRunner(
                mode,
                transactionManager == null ? null : new TransactionTemplate(transactionManager)
        );
        return new EvidenceGatedFinalizeStartupGuard(
                runner,
                transactionManagerProvider,
                probeProvider,
                outboxRepositoryProvider,
                localAuditPhaseWriterProvider,
                auditChainIndexInitializerProvider,
                validLocalAuditPhaseWriterProperties(),
                recoveryStrategies,
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                globalEnabled,
                submitDecisionEnabled,
                false,
                false,
                false,
                probeEnabled,
                outboxRecoveryEnabled
        );
    }

    @SuppressWarnings("unchecked")
    private EvidenceGatedFinalizeStartupGuard guardWithUnsupportedMutation(
            boolean fraudCaseUpdateEnabled,
            boolean trustIncidentEnabled,
            boolean outboxResolutionEnabled
    ) {
        ObjectProvider<PlatformTransactionManager> transactionManagerProvider = mock(ObjectProvider.class);
        when(transactionManagerProvider.getIfAvailable()).thenReturn(mock(PlatformTransactionManager.class));
        ObjectProvider<RegulatedMutationTransactionCapabilityProbe> probeProvider = mock(ObjectProvider.class);
        when(probeProvider.getIfAvailable()).thenReturn(mock(RegulatedMutationTransactionCapabilityProbe.class));
        ObjectProvider<TransactionalOutboxRecordRepository> outboxRepositoryProvider = mock(ObjectProvider.class);
        when(outboxRepositoryProvider.getIfAvailable()).thenReturn(mock(TransactionalOutboxRecordRepository.class));
        ObjectProvider<RegulatedMutationLocalAuditPhaseWriter> localAuditPhaseWriterProvider = mock(ObjectProvider.class);
        when(localAuditPhaseWriterProvider.getIfAvailable()).thenReturn(mock(RegulatedMutationLocalAuditPhaseWriter.class));
        AuditChainIndexInitializer auditChainIndexInitializer = mockAuditChainIndexInitializer(true);
        ObjectProvider<AuditChainIndexInitializer> auditChainIndexInitializerProvider = mock(ObjectProvider.class);
        when(auditChainIndexInitializerProvider.getIfAvailable()).thenReturn(auditChainIndexInitializer);
        return new EvidenceGatedFinalizeStartupGuard(
                new RegulatedMutationTransactionRunner(
                        RegulatedMutationTransactionMode.REQUIRED,
                        new TransactionTemplate(mock(PlatformTransactionManager.class))
                ),
                transactionManagerProvider,
                probeProvider,
                outboxRepositoryProvider,
                localAuditPhaseWriterProvider,
                auditChainIndexInitializerProvider,
                validLocalAuditPhaseWriterProperties(),
                List.of(mockSubmitDecisionRecovery()),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                true,
                true,
                fraudCaseUpdateEnabled,
                trustIncidentEnabled,
                outboxResolutionEnabled,
                true,
                true
        );
    }

    @SuppressWarnings("unchecked")
    private EvidenceGatedFinalizeStartupGuard guardWithAuditWriterConfig(
            RegulatedMutationTransactionMode mode,
            boolean globalEnabled,
            boolean submitDecisionEnabled,
            boolean probeEnabled,
            boolean outboxRecoveryEnabled,
            PlatformTransactionManager transactionManager,
            RegulatedMutationTransactionCapabilityProbe probe,
            TransactionalOutboxRecordRepository outboxRepository,
            RegulatedMutationLocalAuditPhaseWriter localAuditPhaseWriter,
            LocalAuditPhaseWriterProperties localAuditPhaseWriterProperties,
            AuditChainIndexInitializer auditChainIndexInitializer,
            List<RegulatedMutationRecoveryStrategy> recoveryStrategies
    ) {
        ObjectProvider<PlatformTransactionManager> transactionManagerProvider = mock(ObjectProvider.class);
        when(transactionManagerProvider.getIfAvailable()).thenReturn(transactionManager);
        ObjectProvider<RegulatedMutationTransactionCapabilityProbe> probeProvider = mock(ObjectProvider.class);
        when(probeProvider.getIfAvailable()).thenReturn(probe);
        ObjectProvider<TransactionalOutboxRecordRepository> outboxRepositoryProvider = mock(ObjectProvider.class);
        when(outboxRepositoryProvider.getIfAvailable()).thenReturn(outboxRepository);
        ObjectProvider<RegulatedMutationLocalAuditPhaseWriter> localAuditPhaseWriterProvider = mock(ObjectProvider.class);
        when(localAuditPhaseWriterProvider.getIfAvailable()).thenReturn(localAuditPhaseWriter);
        ObjectProvider<AuditChainIndexInitializer> auditChainIndexInitializerProvider = mock(ObjectProvider.class);
        when(auditChainIndexInitializerProvider.getIfAvailable()).thenReturn(auditChainIndexInitializer);
        return new EvidenceGatedFinalizeStartupGuard(
                new RegulatedMutationTransactionRunner(
                        mode,
                        transactionManager == null ? null : new TransactionTemplate(transactionManager)
                ),
                transactionManagerProvider,
                probeProvider,
                outboxRepositoryProvider,
                localAuditPhaseWriterProvider,
                auditChainIndexInitializerProvider,
                localAuditPhaseWriterProperties,
                recoveryStrategies,
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                globalEnabled,
                submitDecisionEnabled,
                false,
                false,
                false,
                probeEnabled,
                outboxRecoveryEnabled
        );
    }

    private LocalAuditPhaseWriterProperties validLocalAuditPhaseWriterProperties() {
        LocalAuditPhaseWriterProperties properties = new LocalAuditPhaseWriterProperties();
        properties.setMaxAppendAttempts(10);
        properties.setBackoffMs(1);
        properties.setMaxTotalWaitMs(100);
        return properties;
    }

    private AuditChainIndexInitializer mockAuditChainIndexInitializer(boolean requiredIndexesPresent) {
        AuditChainIndexInitializer initializer = mock(AuditChainIndexInitializer.class);
        when(initializer.hasRequiredUniqueIndexes()).thenReturn(requiredIndexesPresent);
        return initializer;
    }

    private RegulatedMutationRecoveryStrategy mockSubmitDecisionRecovery() {
        RegulatedMutationRecoveryStrategy strategy = mock(RegulatedMutationRecoveryStrategy.class);
        when(strategy.supports(
                com.frauddetection.alert.audit.AuditAction.SUBMIT_ANALYST_DECISION,
                com.frauddetection.alert.audit.AuditResourceType.ALERT
        )).thenReturn(true);
        return strategy;
    }
}
