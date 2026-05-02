package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegulatedMutationStartupGuardTest {

    @Test
    void shouldAllowLocalModeWithoutRequiredTransactions() {
        RegulatedMutationStartupGuard guard = guard(
                RegulatedMutationTransactionMode.OFF,
                false,
                new String[]{"local"},
                null,
                true,
                true,
                true,
                5
        );

        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectBankModeWhenTransactionsAreOff() {
        RegulatedMutationStartupGuard guard = guard(
                RegulatedMutationTransactionMode.OFF,
                true,
                new String[]{"local"},
                mock(PlatformTransactionManager.class),
                true,
                true,
                true,
                5
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction-mode=REQUIRED");
    }

    @Test
    void shouldRejectProdProfileWhenTransactionManagerIsMissing() {
        RegulatedMutationStartupGuard guard = guard(
                RegulatedMutationTransactionMode.REQUIRED,
                false,
                new String[]{"prod"},
                null,
                true,
                true,
                true,
                5
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires a Mongo transaction manager");
    }

    @Test
    void shouldRejectProdProfileWhenOutboxRecoveryIsDisabled() {
        RegulatedMutationStartupGuard guard = guard(
                RegulatedMutationTransactionMode.REQUIRED,
                false,
                new String[]{"prod"},
                mock(PlatformTransactionManager.class),
                true,
                false,
                true,
                5
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outbox recovery enabled");
    }

    @Test
    void shouldRejectRequiredModeWhenTransactionCapabilityProbeFails() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        RegulatedMutationTransactionCapabilityProbe probe = mock(RegulatedMutationTransactionCapabilityProbe.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("transactions unavailable")).when(probe).verify();
        RegulatedMutationStartupGuard guard = guard(
                RegulatedMutationTransactionMode.REQUIRED,
                false,
                new String[]{"local"},
                transactionManager,
                true,
                true,
                true,
                5,
                probe,
                mock(TransactionalOutboxRecordRepository.class)
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transactions unavailable");
    }

    @Test
    void shouldRunTransactionCapabilityProbeWhenRequiredModeIsEnabled() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        RegulatedMutationTransactionCapabilityProbe probe = mock(RegulatedMutationTransactionCapabilityProbe.class);
        RegulatedMutationStartupGuard guard = guard(
                RegulatedMutationTransactionMode.REQUIRED,
                false,
                new String[]{"local"},
                transactionManager,
                true,
                true,
                true,
                5,
                probe,
                mock(TransactionalOutboxRecordRepository.class)
        );

        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
        verify(probe).verify();
    }

    @Test
    void shouldSkipTransactionCapabilityProbeWhenTransactionModeIsOff() {
        RegulatedMutationTransactionCapabilityProbe probe = mock(RegulatedMutationTransactionCapabilityProbe.class);
        RegulatedMutationStartupGuard guard = guard(
                RegulatedMutationTransactionMode.OFF,
                false,
                new String[]{"local"},
                null,
                true,
                true,
                true,
                5,
                probe,
                null
        );

        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
        verify(probe, never()).verify();
    }

    @SuppressWarnings("unchecked")
    private RegulatedMutationStartupGuard guard(
            RegulatedMutationTransactionMode mode,
            boolean bankMode,
            String[] profiles,
            PlatformTransactionManager transactionManager,
            boolean outboxPublisherEnabled,
            boolean outboxRecoveryEnabled,
            boolean transactionCapabilityProbeEnabled,
            int maxAttempts
    ) {
        return guard(
                mode,
                bankMode,
                profiles,
                transactionManager,
                outboxPublisherEnabled,
                outboxRecoveryEnabled,
                transactionCapabilityProbeEnabled,
                maxAttempts,
                mode == RegulatedMutationTransactionMode.REQUIRED ? mock(RegulatedMutationTransactionCapabilityProbe.class) : null,
                transactionManager == null ? null : mock(TransactionalOutboxRecordRepository.class)
        );
    }

    @SuppressWarnings("unchecked")
    private RegulatedMutationStartupGuard guard(
            RegulatedMutationTransactionMode mode,
            boolean bankMode,
            String[] profiles,
            PlatformTransactionManager transactionManager,
            boolean outboxPublisherEnabled,
            boolean outboxRecoveryEnabled,
            boolean transactionCapabilityProbeEnabled,
            int maxAttempts,
            RegulatedMutationTransactionCapabilityProbe probe,
            TransactionalOutboxRecordRepository outboxRepository
    ) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(profiles);
        ObjectProvider<PlatformTransactionManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(transactionManager);
        ObjectProvider<TransactionalOutboxRecordRepository> outboxProvider = mock(ObjectProvider.class);
        when(outboxProvider.getIfAvailable()).thenReturn(outboxRepository);
        ObjectProvider<RegulatedMutationTransactionCapabilityProbe> probeProvider = mock(ObjectProvider.class);
        when(probeProvider.getIfAvailable()).thenReturn(probe);
        RegulatedMutationTransactionRunner runner = new RegulatedMutationTransactionRunner(
                mode,
                transactionManager == null ? null : new TransactionTemplate(transactionManager)
        );
        return new RegulatedMutationStartupGuard(
                runner,
                provider,
                outboxProvider,
                probeProvider,
                environment,
                bankMode,
                outboxPublisherEnabled,
                outboxRecoveryEnabled,
                transactionCapabilityProbeEnabled,
                maxAttempts
        );
    }
}
