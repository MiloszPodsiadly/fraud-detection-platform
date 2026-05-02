package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegulatedMutationTransactionRunnerTest {

    @Test
    void shouldRunDirectlyWhenTransactionModeOff() {
        RegulatedMutationTransactionRunner runner = new RegulatedMutationTransactionRunner(
                RegulatedMutationTransactionMode.OFF,
                null
        );

        String result = runner.runLocalCommit(() -> "committed");

        assertThat(result).isEqualTo("committed");
    }

    @Test
    void shouldFailClosedWhenRequiredModeHasNoTransactionManager() {
        RegulatedMutationTransactionRunner runner = new RegulatedMutationTransactionRunner(
                RegulatedMutationTransactionMode.REQUIRED,
                null
        );

        assertThatThrownBy(() -> runner.runLocalCommit(() -> "committed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction-mode=REQUIRED requires a Mongo transaction manager");
    }

    @Test
    void shouldExecuteCallbackInsideTransactionWhenRequired() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        RegulatedMutationTransactionRunner runner = new RegulatedMutationTransactionRunner(
                RegulatedMutationTransactionMode.REQUIRED,
                new TransactionTemplate(transactionManager)
        );
        AtomicBoolean called = new AtomicBoolean(false);

        String result = runner.runLocalCommit(() -> {
            called.set(true);
            return "committed";
        });

        assertThat(result).isEqualTo("committed");
        assertThat(called).isTrue();
        verify(transactionManager).commit(any());
    }
}
