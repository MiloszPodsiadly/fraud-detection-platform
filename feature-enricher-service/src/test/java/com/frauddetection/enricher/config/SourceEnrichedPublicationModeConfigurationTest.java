package com.frauddetection.enricher.config;

import com.frauddetection.common.events.source.SourceLineageAccess;
import com.frauddetection.common.events.source.SourceLineageProperties;
import com.frauddetection.enricher.messaging.TransactionEnrichedEventPublisher;
import com.frauddetection.enricher.persistence.FeatureStore;
import com.frauddetection.enricher.service.SourceEnrichedPublicationRecovery;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SourceEnrichedPublicationModeConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SourceEnrichedPublicationRecoveryConfiguration.class, TestBeans.class);

    @Test
    void legacyDefaultsKeepRecoveryDisabledAndRegisterNoPollTask() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(SourceEnrichedPublicationRecovery.class);
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).isEmpty();
        });
    }

    @Test
    void attestedModeRequiresItsIndependentRecoveryOwner() {
        runner.withPropertyValues("fraud.source-lineage.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("SOURCE_ENRICHED_RECOVERY_OWNER_REQUIRED");
        });
    }

    @Test
    void enabledOwnerUsesBoundIntervalsAsActualScheduledTaskMetadata() {
        runner.withPropertyValues("fraud.source-lineage.enabled=true",
                        "fraud.source-enriched-publications.recovery.enabled=true",
                        "fraud.source-enriched-publications.recovery.initial-delay-millis=7000",
                        "fraud.source-enriched-publications.recovery.fixed-delay-millis=60000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SourceEnrichedPublicationRecovery.class);
                    var tasks = context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
                    assertThat(tasks).hasSize(1);
                    FixedDelayTask task = (FixedDelayTask) tasks.iterator().next().getTask();
                    assertThat(task.getInitialDelayDuration()).hasMillis(7000);
                    assertThat(task.getIntervalDuration()).hasMillis(60000);
                });
    }

    @Test
    void operatorModeDoesNotRegisterSchedulersAndRequiresAnEnabledOneShotRequest() {
        runner.withPropertyValues("fraud.source-enriched-publications.redrive.operator-mode=true",
                        "spring.main.web-application-type=none")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("SOURCE_ENRICHED_REDRIVE_OPERATOR_MODE_REQUIRED");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean SourceLineageProperties sourceLineageProperties(org.springframework.core.env.Environment environment) {
            boolean enabled = environment.getProperty("fraud.source-lineage.enabled", Boolean.class, false);
            return new SourceLineageProperties(enabled, enabled ? "mongodb://localhost/test" : null,
                    enabled ? "source_test" : null, enabled ? "enricher" : null);
        }
        @Bean SourceLineageAccess sourceLineageAccess() { return mock(SourceLineageAccess.class); }
        @Bean TransactionEnrichedEventPublisher transactionEnrichedEventPublisher() { return mock(TransactionEnrichedEventPublisher.class); }
        @Bean FeatureStore featureStore() { return mock(FeatureStore.class); }
    }
}
