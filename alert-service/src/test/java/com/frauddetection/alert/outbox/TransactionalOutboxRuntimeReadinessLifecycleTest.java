package com.frauddetection.alert.outbox;

import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.mapper.FraudDecisionEventMapper;
import com.frauddetection.alert.regulated.BankModeStartupGuard;
import com.frauddetection.alert.regulated.EvidenceGatedFinalizeStartupGuard;
import com.frauddetection.alert.regulated.RegulatedMutationPersistedModelPreflight;
import com.frauddetection.alert.regulated.RegulatedMutationPersistedModelStartupGuard;
import com.frauddetection.alert.service.DecisionOutboxWriter;
import com.frauddetection.alert.service.FraudDecisionOutboxPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TransactionalOutboxRuntimeReadinessLifecycleTest {

    @Test
    void bankModeStartupFailureAfterOutboxPreflightKeepsRuntimeClosed() {
        BankModeStartupGuard laterGuard = mock(BankModeStartupGuard.class);
        doThrow(new IllegalStateException("bank mode startup failed"))
                .when(laterGuard).run(any(ApplicationArguments.class));

        assertLaterStartupFailureKeepsRuntimeClosed(laterGuard);
    }

    @Test
    void evidenceGatedFinalizeStartupFailureAfterOutboxPreflightKeepsRuntimeClosed() {
        EvidenceGatedFinalizeStartupGuard laterGuard = mock(EvidenceGatedFinalizeStartupGuard.class);
        doThrow(new IllegalStateException("evidence-gated startup failed"))
                .when(laterGuard).run(any(ApplicationArguments.class));

        assertLaterStartupFailureKeepsRuntimeClosed(laterGuard);
    }

    @Test
    void persistedMutationModelStartupFailureKeepsRuntimeClosed() {
        RegulatedMutationPersistedModelPreflight preflight = mock(RegulatedMutationPersistedModelPreflight.class);
        when(preflight.inspect(25)).thenReturn(new RegulatedMutationPersistedModelPreflight.Report(1, 0, List.of()));

        assertLaterStartupFailureKeepsRuntimeClosed(new RegulatedMutationPersistedModelStartupGuard(preflight));
    }

    @Test
    void contextRefreshDoesNotEnableScheduledPublication() {
        TransactionalOutboxRuntimeReadiness readiness = new TransactionalOutboxRuntimeReadiness();
        readiness.markPreflightPassed();
        OutboxPublisherCoordinator coordinator = mock(OutboxPublisherCoordinator.class);

        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(TransactionalOutboxRuntimeReadiness.class, () -> readiness);
            context.refresh();

            new FraudDecisionOutboxPublisher(coordinator, readiness).publishPending();

            assertThat(readiness.isReady()).isFalse();
            verifyNoInteractions(coordinator);
        }
    }

    @Test
    void recoveryAndAuthoritativeWritesRemainClosedBeforeApplicationReady() {
        TransactionalOutboxRuntimeReadiness readiness = preflightPassedReadiness();
        OutboxRecoveryService recoveryService = mock(OutboxRecoveryService.class);
        OutboxRecoveryController controller = new OutboxRecoveryController(
                recoveryService,
                mock(SensitiveReadAuditService.class),
                readiness
        );
        TransactionalOutboxRecordRepository repository = mock(TransactionalOutboxRecordRepository.class);
        DecisionOutboxWriter writer = new DecisionOutboxWriter(
                new FraudDecisionEventMapper(),
                repository,
                readiness
        );

        assertThatThrownBy(controller::recoverNow).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> controller.resolveConfirmation("event-1", "idem-1", null, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> writer.attachPendingOutbox(null, null, null, null, null, null))
                .isInstanceOf(IllegalStateException.class);

        verify(recoveryService, never()).recoverNow();
        verify(recoveryService, never()).resolveConfirmation(any(), any(), any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void applicationReadyEventAfterSuccessfulPreflightEnablesRuntimeOperations() {
        TransactionalOutboxRuntimeReadiness readiness = new TransactionalOutboxRuntimeReadiness();

        try (ConfigurableApplicationContext ignored = application(readiness, null).run()) {
            assertThat(readiness.isReady()).isTrue();

            OutboxPublisherCoordinator coordinator = mock(OutboxPublisherCoordinator.class);
            when(coordinator.publishPending(10)).thenReturn(1);
            assertThat(new FraudDecisionOutboxPublisher(coordinator, readiness).publishPending(10)).isOne();
            verify(coordinator).publishPending(10);

            OutboxRecoveryService recoveryService = mock(OutboxRecoveryService.class);
            when(recoveryService.recoverNow()).thenReturn(new OutboxRecoveryRunResponse(0, 0, 0, 1));
            OutboxRecoveryController controller = new OutboxRecoveryController(
                    recoveryService,
                    mock(SensitiveReadAuditService.class),
                    readiness
            );
            assertThat(controller.recoverNow().publishAttempted()).isOne();
            verify(recoveryService).recoverNow();
        }
    }

    @Test
    void failedOutboxPreflightCannotBeReopenedByApplicationReadyEvent() {
        TransactionalOutboxRuntimeReadiness readiness = new TransactionalOutboxRuntimeReadiness();
        readiness.markFailed();

        readiness.onApplicationEvent(mock(org.springframework.boot.context.event.ApplicationReadyEvent.class));

        assertThat(readiness.isReady()).isFalse();
        assertThatThrownBy(readiness::requireReady).isInstanceOf(IllegalStateException.class);
    }

    private void assertLaterStartupFailureKeepsRuntimeClosed(ApplicationRunner laterGuard) {
        TransactionalOutboxRuntimeReadiness readiness = new TransactionalOutboxRuntimeReadiness();

        assertThatThrownBy(() -> application(readiness, laterGuard).run())
                .isInstanceOf(IllegalStateException.class);

        assertThat(readiness.isReady()).isFalse();
        assertRuntimeMutationsBlocked(readiness);
    }

    private void assertRuntimeMutationsBlocked(TransactionalOutboxRuntimeReadiness readiness) {
        OutboxPublisherCoordinator coordinator = mock(OutboxPublisherCoordinator.class);
        new FraudDecisionOutboxPublisher(coordinator, readiness).publishPending();
        verifyNoInteractions(coordinator);

        OutboxRecoveryService recoveryService = mock(OutboxRecoveryService.class);
        OutboxRecoveryController controller = new OutboxRecoveryController(
                recoveryService,
                mock(SensitiveReadAuditService.class),
                readiness
        );
        assertThatThrownBy(controller::recoverNow).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> controller.resolveConfirmation("event-1", "idem-1", null, null))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(recoveryService);

        TransactionalOutboxRecordRepository repository = mock(TransactionalOutboxRecordRepository.class);
        DecisionOutboxWriter writer = new DecisionOutboxWriter(
                new FraudDecisionEventMapper(),
                repository,
                readiness
        );
        assertThatThrownBy(() -> writer.attachPendingOutbox(null, null, null, null, null, null))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }

    private TransactionalOutboxRuntimeReadiness preflightPassedReadiness() {
        TransactionalOutboxRuntimeReadiness readiness = new TransactionalOutboxRuntimeReadiness();
        readiness.markPreflightPassed();
        return readiness;
    }

    private SpringApplicationBuilder application(
            TransactionalOutboxRuntimeReadiness readiness,
            ApplicationRunner laterGuard
    ) {
        return new SpringApplicationBuilder(PassingOutboxPreflightConfiguration.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .properties(
                        "spring.main.banner-mode=off",
                        "logging.level.org.springframework.boot.SpringApplication=OFF"
                )
                .initializers(context -> {
                    GenericApplicationContext genericContext = (GenericApplicationContext) context;
                    genericContext.registerBean(
                            "transactionalOutboxRuntimeReadiness",
                            TransactionalOutboxRuntimeReadiness.class,
                            () -> readiness
                    );
                    if (laterGuard != null) {
                        genericContext.registerBean("laterStartupGuard", ApplicationRunner.class, () -> laterGuard);
                    }
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class PassingOutboxPreflightConfiguration {

        @Bean
        TransactionalOutboxPersistedContractPreflight transactionalOutboxPersistedContractPreflight() {
            TransactionalOutboxPersistedContractPreflight preflight =
                    mock(TransactionalOutboxPersistedContractPreflight.class);
            when(preflight.inspect(25)).thenReturn(
                    new TransactionalOutboxPersistedContractPreflight.Report(0, 0, 0, List.of())
            );
            return preflight;
        }

        @Bean
        TransactionalOutboxPersistedContractStartupGuard transactionalOutboxPersistedContractStartupGuard(
                TransactionalOutboxPersistedContractPreflight preflight,
                TransactionalOutboxRuntimeReadiness readiness
        ) {
            return new TransactionalOutboxPersistedContractStartupGuard(preflight, readiness);
        }
    }
}
