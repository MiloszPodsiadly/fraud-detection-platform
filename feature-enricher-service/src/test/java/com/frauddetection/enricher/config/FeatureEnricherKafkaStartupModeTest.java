package com.frauddetection.enricher.config;

import com.frauddetection.common.events.source.SourceLineageAccess;
import com.frauddetection.enricher.messaging.TransactionRawEventListener;
import com.frauddetection.enricher.service.SourceEnrichedPublicationRedriveRunner;
import com.frauddetection.enricher.service.TransactionFeatureEnrichmentUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeatureEnricherKafkaStartupModeTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(KafkaTestConfiguration.class,
                FeatureEnricherKafkaConfig.class, SourceEnrichedPublicationRecoveryConfiguration.class,
                SourceEnrichedPublicationModeConfigurationTest.TestBeans.class);
    }

    @Test
    void oneShotRedriveStartsNoKafkaListenerOrSchedulerAndPerformsExactlyOneRequest() {
        runner().withPropertyValues("spring.kafka.bootstrap-servers=localhost:9092",
                        "spring.kafka.listener.auto-startup=false",
                        "spring.main.web-application-type=none",
                        "app.kafka.topics.transaction-raw=raw", "app.kafka.topics.transaction-enriched=enriched",
                        "app.kafka.topics.transactions-dead-letter=raw.dlt",
                        "app.kafka.consumer.concurrency=1", "app.kafka.consumer.retry-attempts=3",
                        "app.kafka.consumer.retry-backoff-millis=0",
                        "app.feature-store.recent-transaction-window=PT1M", "app.feature-store.merchant-frequency-window=P7D",
                        "app.feature-store.transaction-key-ttl=P8D", "app.feature-store.known-device-ttl=P180D",
                        "app.feature-store.last-transaction-ttl=P30D",
                        "fraud.source-lineage.enabled=true",
                        "fraud.source-enriched-publications.redrive.operator-mode=true",
                        "fraud.source-enriched-publications.redrive.enabled=true",
                        "fraud.source-enriched-publications.redrive.publication-id=" + "a".repeat(64),
                        "fraud.source-enriched-publications.redrive.reason=OPERATOR_RECONCILIATION")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var registry = context.getBean(KafkaListenerEndpointRegistry.class);
                    assertThat(registry.getListenerContainers()).hasSize(1)
                            .allSatisfy(container -> assertThat(container.isRunning()).isFalse());
                    var factory = context.getBean("transactionRawKafkaListenerContainerFactory",
                            ConcurrentKafkaListenerContainerFactory.class);
                    assertThat(factory.createContainer("raw").isAutoStartup()).isFalse();
                    assertThat(context.getBeansOfType(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class))
                            .isEmpty();

                    SourceLineageAccess lineage = context.getBean(SourceLineageAccess.class);
                    when(lineage.redriveEnrichedPublication("a".repeat(64), "OPERATOR_RECONCILIATION"))
                            .thenReturn(new SourceLineageAccess.EnrichedPublicationRedriveResult(
                                    SourceLineageAccess.EnrichedPublicationRedriveStatus.REQUEUED, 1));
                    context.getBean(SourceEnrichedPublicationRedriveRunner.class)
                            .run(new DefaultApplicationArguments(new String[0]));
                    verify(lineage, times(1)).redriveEnrichedPublication("a".repeat(64), "OPERATOR_RECONCILIATION");
                });
    }

    @Test
    void normalModeLeavesTheCustomListenerEligibleForStartup() {
        runner().withPropertyValues("spring.kafka.bootstrap-servers=localhost:9092",
                        "spring.kafka.consumer.group-id=feature-enricher-test",
                        "app.kafka.topics.transaction-raw=raw", "app.kafka.topics.transaction-enriched=enriched",
                        "app.kafka.topics.transactions-dead-letter=raw.dlt",
                        "app.kafka.consumer.concurrency=1", "app.kafka.consumer.retry-attempts=3",
                        "app.kafka.consumer.retry-backoff-millis=0",
                        "app.feature-store.recent-transaction-window=PT1M", "app.feature-store.merchant-frequency-window=P7D",
                        "app.feature-store.transaction-key-ttl=P8D", "app.feature-store.known-device-ttl=P180D",
                        "app.feature-store.last-transaction-ttl=P30D")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var registry = context.getBean(KafkaListenerEndpointRegistry.class);
                    assertThat(registry.getListenerContainers()).hasSize(1)
                            .allSatisfy(container -> {
                                assertThat(container.isAutoStartup()).isTrue();
                                assertThat(container.isRunning()).isTrue();
                            });
                    var factory = context.getBean("transactionRawKafkaListenerContainerFactory",
                            ConcurrentKafkaListenerContainerFactory.class);
                    assertThat(factory.createContainer("raw").isAutoStartup()).isTrue();
                });
    }

    @Test
    void springListenerDisablePropertyIsAppliedToTheCustomFactoryOutsideRedriveMode() {
        runner().withPropertyValues("spring.kafka.bootstrap-servers=localhost:9092",
                        "spring.kafka.listener.auto-startup=false",
                        "app.kafka.topics.transaction-raw=raw", "app.kafka.topics.transaction-enriched=enriched",
                        "app.kafka.topics.transactions-dead-letter=raw.dlt",
                        "app.kafka.consumer.concurrency=1", "app.kafka.consumer.retry-attempts=3",
                        "app.kafka.consumer.retry-backoff-millis=0",
                        "app.feature-store.recent-transaction-window=PT1M", "app.feature-store.merchant-frequency-window=P7D",
                        "app.feature-store.transaction-key-ttl=P8D", "app.feature-store.known-device-ttl=P180D",
                        "app.feature-store.last-transaction-ttl=P30D")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var factory = context.getBean("transactionRawKafkaListenerContainerFactory",
                            ConcurrentKafkaListenerContainerFactory.class);
                    assertThat(factory.createContainer("raw").isAutoStartup()).isFalse();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableKafka
    @EnableConfigurationProperties(KafkaProperties.class)
    static class KafkaTestConfiguration {
        @Bean TransactionFeatureEnrichmentUseCase transactionFeatureEnrichmentUseCase() {
            return mock(TransactionFeatureEnrichmentUseCase.class);
        }
        @Bean TransactionRawEventListener transactionRawEventListener(TransactionFeatureEnrichmentUseCase useCase,
                                                                         KafkaTopicProperties topics) {
            return new TransactionRawEventListener(useCase, topics);
        }
    }
}
