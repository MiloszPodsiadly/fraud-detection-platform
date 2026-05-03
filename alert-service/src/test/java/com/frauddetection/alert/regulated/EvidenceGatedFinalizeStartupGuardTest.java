package com.frauddetection.alert.regulated;

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
        RegulatedMutationTransactionRunner runner = new RegulatedMutationTransactionRunner(
                mode,
                transactionManager == null ? null : new TransactionTemplate(transactionManager)
        );
        return new EvidenceGatedFinalizeStartupGuard(
                runner,
                transactionManagerProvider,
                probeProvider,
                outboxRepositoryProvider,
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

    private RegulatedMutationRecoveryStrategy mockSubmitDecisionRecovery() {
        RegulatedMutationRecoveryStrategy strategy = mock(RegulatedMutationRecoveryStrategy.class);
        when(strategy.supports(
                com.frauddetection.alert.audit.AuditAction.SUBMIT_ANALYST_DECISION,
                com.frauddetection.alert.audit.AuditResourceType.ALERT
        )).thenReturn(true);
        return strategy;
    }
}
