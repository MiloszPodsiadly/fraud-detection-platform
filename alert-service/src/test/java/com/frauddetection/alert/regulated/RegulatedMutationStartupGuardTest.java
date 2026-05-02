package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
                5
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outbox recovery enabled");
    }

    @SuppressWarnings("unchecked")
    private RegulatedMutationStartupGuard guard(
            RegulatedMutationTransactionMode mode,
            boolean bankMode,
            String[] profiles,
            PlatformTransactionManager transactionManager,
            boolean outboxPublisherEnabled,
            boolean outboxRecoveryEnabled,
            int maxAttempts
    ) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(profiles);
        ObjectProvider<PlatformTransactionManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(transactionManager);
        RegulatedMutationTransactionRunner runner = new RegulatedMutationTransactionRunner(
                mode,
                transactionManager == null ? null : new TransactionTemplate(transactionManager)
        );
        return new RegulatedMutationStartupGuard(
                runner,
                provider,
                environment,
                bankMode,
                outboxPublisherEnabled,
                outboxRecoveryEnabled,
                maxAttempts
        );
    }
}
