package com.frauddetection.alert.system.bankprofile;

import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.regulated.BankModeStartupGuard;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionCapabilityProbe;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionMode;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BankProfileMisconfigurationMatrixTest {

    @Test
    void shouldRejectProdProfileWhenBankFailClosedIsDisabled() {
        BankModeStartupGuard guard = guard(
                new String[]{"prod"},
                false,
                true,
                "object-store",
                true,
                true,
                true
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.audit.bank-mode.fail-closed");
    }

    @Test
    void shouldRejectBankProfileWhenSensitiveReadAuditIsFailOpen() {
        BankModeStartupGuard guard = guard(
                new String[]{"bank"},
                true,
                false,
                "object-store",
                true,
                true,
                true
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.sensitive-reads.audit.fail-closed");
    }

    @Test
    void shouldRejectBankProfileWhenExternalAnchorSinkIsLocal() {
        BankModeStartupGuard guard = guard(
                new String[]{"bank"},
                true,
                true,
                "local-file",
                true,
                true,
                true
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.audit.external-anchoring.sink");
    }

    @Test
    void shouldRejectBankProfileWhenTrustAuthoritySigningIsOptional() {
        BankModeStartupGuard guard = guard(
                new String[]{"bank"},
                true,
                true,
                "object-store",
                true,
                true,
                false
        );

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.audit.trust-authority.signing-required");
    }

    @SuppressWarnings("unchecked")
    private BankModeStartupGuard guard(
            String[] profiles,
            boolean bankModeFailClosed,
            boolean sensitiveReadAuditFailClosed,
            String externalSink,
            boolean externalPublicationEnabled,
            boolean trustAuthorityEnabled,
            boolean trustAuthoritySigningRequired
    ) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(profiles);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        ObjectProvider<PlatformTransactionManager> transactionManagerProvider = mock(ObjectProvider.class);
        when(transactionManagerProvider.getIfAvailable()).thenReturn(transactionManager);
        ObjectProvider<TransactionalOutboxRecordRepository> outboxProvider = mock(ObjectProvider.class);
        when(outboxProvider.getIfAvailable()).thenReturn(mock(TransactionalOutboxRecordRepository.class));
        ObjectProvider<RegulatedMutationTransactionCapabilityProbe> probeProvider = mock(ObjectProvider.class);
        when(probeProvider.getIfAvailable()).thenReturn(mock(RegulatedMutationTransactionCapabilityProbe.class));
        return new BankModeStartupGuard(
                new RegulatedMutationTransactionRunner("REQUIRED", transactionManagerProvider),
                transactionManagerProvider,
                outboxProvider,
                probeProvider,
                environment,
                bankModeFailClosed,
                true,
                true,
                true,
                true,
                sensitiveReadAuditFailClosed,
                externalPublicationEnabled,
                true,
                true,
                trustAuthorityEnabled,
                trustAuthoritySigningRequired,
                externalSink,
                "ATOMIC",
                5
        );
    }
}
