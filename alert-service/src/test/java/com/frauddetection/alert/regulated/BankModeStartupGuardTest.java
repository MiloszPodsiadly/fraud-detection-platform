package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class BankModeStartupGuardTest {

    @Test
    void shouldAllowLocalModeWithoutRequiredTransactions() {
        BankModeStartupGuard guard = guard(
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
        BankModeStartupGuard guard = guard(
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
                .hasMessageContaining("app.regulated-mutations.transaction-mode");
    }

    @Test
    void shouldRejectProdProfileWhenTransactionManagerIsMissing() {
        BankModeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.REQUIRED,
                true,
                new String[]{"prod"},
                null,
                true,
                true,
                true,
                5
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mongo transaction manager");
    }

    @Test
    void shouldRejectProdProfileWhenOutboxRecoveryIsDisabled() {
        BankModeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.REQUIRED,
                true,
                new String[]{"prod"},
                mock(PlatformTransactionManager.class),
                true,
                false,
                true,
                5
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.outbox.recovery.enabled");
    }

    @Test
    void shouldRejectBankModeWhenOutboxConfirmationDualControlIsDisabled() {
        BankModeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.REQUIRED,
                true,
                new String[]{"bank"},
                mock(PlatformTransactionManager.class),
                true,
                true,
                false,
                true,
                5
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.outbox.confirmation.dual-control.enabled");
    }

    @Test
    void shouldRejectRequiredModeWhenTransactionCapabilityProbeFails() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        RegulatedMutationTransactionCapabilityProbe probe = mock(RegulatedMutationTransactionCapabilityProbe.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("transactions unavailable")).when(probe).verify();
        BankModeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.REQUIRED,
                false,
                new String[]{"local"},
                transactionManager,
                true,
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
    void shouldRejectBankModeWhenTransactionCapabilityProbeIsDisabled() {
        BankModeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.REQUIRED,
                true,
                new String[]{"bank"},
                mock(PlatformTransactionManager.class),
                true,
                true,
                true,
                false,
                "ATOMIC",
                5,
                mock(RegulatedMutationTransactionCapabilityProbe.class),
                mock(TransactionalOutboxRecordRepository.class)
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.regulated-mutations.transaction-capability-probe.enabled");
    }

    @Test
    void shouldRejectBankModeWhenTrustIncidentRefreshModeIsPartial() {
        BankModeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.REQUIRED,
                true,
                new String[]{"bank"},
                mock(PlatformTransactionManager.class),
                true,
                true,
                true,
                true,
                "PARTIAL",
                5,
                mock(RegulatedMutationTransactionCapabilityProbe.class),
                mock(TransactionalOutboxRecordRepository.class)
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.trust-incidents.refresh-mode");
    }

    @Test
    void shouldRejectProdProfileWhenTrustIncidentRefreshModeIsPartial() {
        BankModeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.REQUIRED,
                true,
                new String[]{"prod"},
                mock(PlatformTransactionManager.class),
                true,
                true,
                true,
                true,
                "PARTIAL",
                5,
                mock(RegulatedMutationTransactionCapabilityProbe.class),
                mock(TransactionalOutboxRecordRepository.class)
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.trust-incidents.refresh-mode");
    }

    @Test
    void shouldRejectProdProfileWhenTransactionsAreOff() {
        BankModeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.OFF,
                true,
                new String[]{"prod"},
                mock(PlatformTransactionManager.class),
                true,
                true,
                true,
                true,
                "ATOMIC",
                5,
                null,
                mock(TransactionalOutboxRecordRepository.class)
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.regulated-mutations.transaction-mode");
    }

    @Test
    void shouldAllowBankModeWithAtomicTrustRefreshAndRequiredTransactions(CapturedOutput output) {
        BankModeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.REQUIRED,
                true,
                new String[]{"bank"},
                mock(PlatformTransactionManager.class),
                true,
                true,
                true,
                true,
                "ATOMIC",
                5,
                mock(RegulatedMutationTransactionCapabilityProbe.class),
                mock(TransactionalOutboxRecordRepository.class)
        );

        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
        assertThat(output).contains("FDP-27 bank profile active: transaction-mode=REQUIRED, trust-incidents.refresh-mode=ATOMIC, outbox dual-control and sensitive-read fail-closed enabled.");
    }

    @Test
    void shouldRejectBankModeWhenSensitiveReadAuditIsFailOpen() {
        BankModeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.REQUIRED,
                true,
                new String[]{"bank"},
                mock(PlatformTransactionManager.class),
                true,
                true,
                true,
                true,
                "ATOMIC",
                5,
                mock(RegulatedMutationTransactionCapabilityProbe.class),
                mock(TransactionalOutboxRecordRepository.class),
                false,
                false,
                false,
                false,
                false,
                false,
                "disabled"
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.sensitive-reads.audit.fail-closed");
    }

    @Test
    void shouldRejectBankModeWhenExternalPublicationUsesLocalSink() {
        BankModeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.REQUIRED,
                true,
                new String[]{"bank"},
                mock(PlatformTransactionManager.class),
                true,
                true,
                true,
                true,
                "ATOMIC",
                5,
                mock(RegulatedMutationTransactionCapabilityProbe.class),
                mock(TransactionalOutboxRecordRepository.class),
                true,
                true,
                true,
                true,
                false,
                false,
                "local-file"
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.audit.external-anchoring.sink");
    }

    @Test
    void shouldRejectProdProfileWhenBankModeFailClosedIsNotEnabled() {
        BankModeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.REQUIRED,
                false,
                new String[]{"prod"},
                mock(PlatformTransactionManager.class),
                true,
                true,
                true,
                true,
                "ATOMIC",
                5,
                mock(RegulatedMutationTransactionCapabilityProbe.class),
                mock(TransactionalOutboxRecordRepository.class)
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.audit.bank-mode.fail-closed");
    }

    @Test
    void shouldAllowLocalPartialTrustRefreshWithTransactionModeOff() {
        BankModeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.OFF,
                false,
                new String[]{"local"},
                null,
                true,
                true,
                true,
                true,
                "PARTIAL",
                5,
                null,
                null
        );

        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
    }

    @Test
    void shouldRunTransactionCapabilityProbeWhenRequiredModeIsEnabled() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        RegulatedMutationTransactionCapabilityProbe probe = mock(RegulatedMutationTransactionCapabilityProbe.class);
        BankModeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.REQUIRED,
                false,
                new String[]{"local"},
                transactionManager,
                true,
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
        BankModeStartupGuard guard = guard(
                RegulatedMutationTransactionMode.OFF,
                false,
                new String[]{"local"},
                null,
                true,
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
    private BankModeStartupGuard guard(
            RegulatedMutationTransactionMode mode,
            boolean bankMode,
            String[] profiles,
            PlatformTransactionManager transactionManager,
            boolean outboxPublisherEnabled,
            boolean outboxRecoveryEnabled,
            boolean outboxConfirmationDualControlEnabled,
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
                outboxConfirmationDualControlEnabled,
                transactionCapabilityProbeEnabled,
                "ATOMIC",
                maxAttempts,
                mode == RegulatedMutationTransactionMode.REQUIRED ? mock(RegulatedMutationTransactionCapabilityProbe.class) : null,
                transactionManager == null ? null : mock(TransactionalOutboxRecordRepository.class)
        );
    }

    private BankModeStartupGuard guard(
            RegulatedMutationTransactionMode mode,
            boolean bankMode,
            String[] profiles,
            PlatformTransactionManager transactionManager,
            boolean outboxPublisherEnabled,
            boolean outboxRecoveryEnabled,
            boolean transactionCapabilityProbeEnabled,
            int maxAttempts
    ) {
        return guard(mode, bankMode, profiles, transactionManager, outboxPublisherEnabled, outboxRecoveryEnabled, true, transactionCapabilityProbeEnabled, maxAttempts);
    }

    private BankModeStartupGuard guard(
            RegulatedMutationTransactionMode mode,
            boolean bankMode,
            String[] profiles,
            PlatformTransactionManager transactionManager,
            boolean outboxPublisherEnabled,
            boolean outboxRecoveryEnabled,
            boolean outboxConfirmationDualControlEnabled,
            boolean transactionCapabilityProbeEnabled,
            int maxAttempts,
            RegulatedMutationTransactionCapabilityProbe probe,
            TransactionalOutboxRecordRepository outboxRepository
    ) {
        return guard(
                mode,
                bankMode,
                profiles,
                transactionManager,
                outboxPublisherEnabled,
                outboxRecoveryEnabled,
                outboxConfirmationDualControlEnabled,
                transactionCapabilityProbeEnabled,
                "ATOMIC",
                maxAttempts,
                probe,
                outboxRepository,
                true,
                false,
                false,
                false,
                false,
                false,
                "disabled"
        );
    }

    @SuppressWarnings("unchecked")
    private BankModeStartupGuard guard(
            RegulatedMutationTransactionMode mode,
            boolean bankMode,
            String[] profiles,
            PlatformTransactionManager transactionManager,
            boolean outboxPublisherEnabled,
            boolean outboxRecoveryEnabled,
            boolean outboxConfirmationDualControlEnabled,
            boolean transactionCapabilityProbeEnabled,
            String trustIncidentRefreshMode,
            int maxAttempts,
            RegulatedMutationTransactionCapabilityProbe probe,
            TransactionalOutboxRecordRepository outboxRepository,
            boolean sensitiveReadAuditFailClosed,
            boolean externalPublicationEnabled,
            boolean externalPublicationRequired,
            boolean externalPublicationFailClosed,
            boolean trustAuthorityEnabled,
            boolean trustAuthoritySigningRequired,
            String externalAnchoringSink
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
        return new BankModeStartupGuard(
                runner,
                provider,
                outboxProvider,
                probeProvider,
                environment,
                bankMode,
                outboxPublisherEnabled,
                outboxRecoveryEnabled,
                outboxConfirmationDualControlEnabled,
                transactionCapabilityProbeEnabled,
                sensitiveReadAuditFailClosed,
                externalPublicationEnabled,
                externalPublicationRequired,
                externalPublicationFailClosed,
                trustAuthorityEnabled,
                trustAuthoritySigningRequired,
                externalAnchoringSink,
                trustIncidentRefreshMode,
                maxAttempts
        );
    }

    @SuppressWarnings("unchecked")
    private BankModeStartupGuard guard(
            RegulatedMutationTransactionMode mode,
            boolean bankMode,
            String[] profiles,
            PlatformTransactionManager transactionManager,
            boolean outboxPublisherEnabled,
            boolean outboxRecoveryEnabled,
            boolean outboxConfirmationDualControlEnabled,
            boolean transactionCapabilityProbeEnabled,
            String trustIncidentRefreshMode,
            int maxAttempts,
            RegulatedMutationTransactionCapabilityProbe probe,
            TransactionalOutboxRecordRepository outboxRepository
    ) {
        return guard(
                mode,
                bankMode,
                profiles,
                transactionManager,
                outboxPublisherEnabled,
                outboxRecoveryEnabled,
                outboxConfirmationDualControlEnabled,
                transactionCapabilityProbeEnabled,
                trustIncidentRefreshMode,
                maxAttempts,
                probe,
                outboxRepository,
                true,
                false,
                false,
                false,
                false,
                false,
                "disabled"
        );
    }
}
