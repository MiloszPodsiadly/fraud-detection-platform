package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.RegulatedMutationLocalAuditPhaseWriter;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RegulatedMutationCheckpointRenewalWiringTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AlertServiceMetrics metrics = new AlertServiceMetrics(meterRegistry);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final RegulatedMutationLeaseRenewalPolicy renewalPolicy = new RegulatedMutationLeaseRenewalPolicy(
            Duration.ofSeconds(10),
            Duration.ofSeconds(60),
            3
    );
    private final RegulatedMutationLeaseRenewalFailureHandler failureHandler =
            new RegulatedMutationLeaseRenewalFailureHandler(
                    mongoTemplate,
                    renewalPolicy,
                    new RegulatedMutationPublicStatusMapper()
            );
    private final RegulatedMutationLeaseRenewalService leaseRenewalService =
            new RegulatedMutationLeaseRenewalService(
                    mongoTemplate,
                    renewalPolicy,
                    failureHandler,
                    metrics
            );
    private final RegulatedMutationCheckpointRenewalService checkpointRenewalService =
            new RegulatedMutationCheckpointRenewalService(
                    new RegulatedMutationSafeCheckpointPolicy(),
                    leaseRenewalService,
                    metrics,
                    Duration.ofSeconds(10),
                    java.time.Clock.systemUTC()
            );

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("app.audit.bank-mode.fail-closed=false")
            .withBean(SimpleMeterRegistry.class, () -> meterRegistry)
            .withBean(AlertServiceMetrics.class, () -> metrics)
            .withBean(MongoTemplate.class, () -> mongoTemplate)
            .withBean(RegulatedMutationCommandRepository.class, () -> mock(RegulatedMutationCommandRepository.class))
            .withBean(RegulatedMutationAuditPhaseService.class, () -> mock(RegulatedMutationAuditPhaseService.class))
            .withBean(AuditDegradationService.class, () -> mock(AuditDegradationService.class))
            .withBean(RegulatedMutationLocalAuditPhaseWriter.class, () -> mock(RegulatedMutationLocalAuditPhaseWriter.class))
            .withBean(RegulatedMutationPublicStatusMapper.class)
            .withBean(RegulatedMutationConflictPolicy.class)
            .withBean(RegulatedMutationLeasePolicy.class)
            .withBean(RegulatedMutationReplayPolicyRegistry.class, () -> new RegulatedMutationReplayPolicyRegistry(
                    List.of(
                            new LegacyRegulatedMutationReplayPolicy(new RegulatedMutationLeasePolicy()),
                            new EvidenceGatedFinalizeReplayPolicy(new RegulatedMutationLeasePolicy())
                    ),
                    true
            ))
            .withBean(RegulatedMutationReplayResolver.class)
            .withBean(RegulatedMutationFencedCommandWriter.class)
            .withBean(RegulatedMutationTransactionRunner.class, () ->
                    new RegulatedMutationTransactionRunner(RegulatedMutationTransactionMode.REQUIRED, null))
            .withBean(RegulatedMutationClaimService.class, () ->
                    new RegulatedMutationClaimService(mongoTemplate, Duration.ofSeconds(30), metrics))
            .withBean(RegulatedMutationLeaseRenewalPolicy.class, () -> renewalPolicy)
            .withBean(RegulatedMutationLeaseRenewalFailureHandler.class, () -> failureHandler)
            .withBean(RegulatedMutationLeaseRenewalService.class, () -> leaseRenewalService)
            .withBean(RegulatedMutationSafeCheckpointPolicy.class)
            .withBean(RegulatedMutationCheckpointRenewalService.class, () -> checkpointRenewalService)
            .withBean(EvidencePreconditionEvaluator.class)
            .withBean(LegacyRegulatedMutationExecutor.class)
            .withBean(EvidenceGatedFinalizeExecutor.class);

    @Test
    void productionExecutorsUseEnabledSpringManagedCheckpointRenewalService() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RegulatedMutationCheckpointRenewalService.class);
            RegulatedMutationCheckpointRenewalService service =
                    context.getBean(RegulatedMutationCheckpointRenewalService.class);
            assertThat(service.isEnabledForTesting()).isTrue();

            RegulatedMutationCheckpointRenewalService legacyService =
                    checkpointServiceFrom(context.getBean(LegacyRegulatedMutationExecutor.class));
            RegulatedMutationCheckpointRenewalService evidenceService =
                    checkpointServiceFrom(context.getBean(EvidenceGatedFinalizeExecutor.class));
            assertThat(legacyService).isSameAs(service);
            assertThat(evidenceService).isSameAs(service);
            assertThat(legacyService.isEnabledForTesting()).isTrue();
            assertThat(evidenceService.isEnabledForTesting()).isTrue();
        });
    }

    @Test
    void disabledCheckpointRenewalServiceIsExplicitlyMarkedDisabled() {
        assertThat(RegulatedMutationCheckpointRenewalService.disabled().isEnabledForTesting()).isFalse();
    }

    private RegulatedMutationCheckpointRenewalService checkpointServiceFrom(Object executor) throws Exception {
        Field field = executor.getClass().getDeclaredField("checkpointRenewalService");
        field.setAccessible(true);
        return (RegulatedMutationCheckpointRenewalService) field.get(executor);
    }
}
