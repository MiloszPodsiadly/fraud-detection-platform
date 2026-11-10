package com.frauddetection.ingest.source;

import com.frauddetection.common.events.source.*;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.mapper.TransactionScoredEventMapper;
import com.frauddetection.scoring.messaging.TransactionScoredEventPublisher;
import com.frauddetection.scoring.observability.ScoringMetrics;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionService;
import com.frauddetection.scoring.service.AnalystRecommendationService;
import com.frauddetection.scoring.service.FraudScoringEngine;
import com.frauddetection.scoring.service.TransactionFraudScoringService;
import com.mongodb.client.MongoClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SourceLineageConfigurationTest {
    @ParameterizedTest
    @ValueSource(classes = {com.frauddetection.enricher.config.SourceLineageConfiguration.class,
            com.frauddetection.scoring.config.SourceLineageConfiguration.class})
    void defaultRuntimeHasNoConnectionOrSourceGrant(Class<?> configuration) {
        new ApplicationContextRunner().withUserConfiguration(configuration).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(MongoClient.class);
            assertThat(context.getBean(SourceVerificationProperties.class).keys()).isEmpty();
            assertThat(context.getBean(SourceLineageAccess.class).enriched(null).state()).isEqualTo(SourceLineage.State.UNVERIFIED);
        });
    }

    @ParameterizedTest
    @ValueSource(classes = {com.frauddetection.enricher.config.SourceLineageConfiguration.class,
            com.frauddetection.scoring.config.SourceLineageConfiguration.class})
    void originClassificationDoesNotRequireOptionalMongoWhenAttestedLineageIsDisabled(Class<?> configuration) {
        String key = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        new ApplicationContextRunner().withUserConfiguration(configuration)
                .withPropertyValues("fraud.source-origin.enabled=true", "fraud.source-origin.active-key-id=origin-key-1",
                        "fraud.source-origin.keys[origin-key-1]=" + key).run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(MongoClient.class);
                    assertThat(context).hasSingleBean(SourceOriginProofs.class);
                    assertThat(context.getBean(SourceLineageAccess.class).isLegacyOnly()).isTrue();
                });
    }

    @Test void actualScoringServiceBeanStartsWithOriginClassificationAndNoSidecar() {
        String key = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        new ApplicationContextRunner().withUserConfiguration(com.frauddetection.scoring.config.SourceLineageConfiguration.class,
                ScorerBeanConfiguration.class).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(TransactionFraudScoringService.class)
                            .doesNotHaveBean(MongoClient.class).doesNotHaveBean(SourceOriginProofs.class);
                });
        new ApplicationContextRunner().withUserConfiguration(com.frauddetection.scoring.config.SourceLineageConfiguration.class,
                ScorerBeanConfiguration.class).withPropertyValues("fraud.source-origin.enabled=true",
                "fraud.source-origin.active-key-id=origin-key-1", "fraud.source-origin.keys[origin-key-1]=" + key).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(TransactionFraudScoringService.class)
                            .doesNotHaveBean(MongoClient.class).hasSingleBean(SourceOriginProofs.class);
                });
    }

    @ParameterizedTest
    @ValueSource(classes = {com.frauddetection.enricher.config.SourceLineageConfiguration.class,
            com.frauddetection.scoring.config.SourceLineageConfiguration.class})
    void enablingWithoutIndependentAuthenticatedServiceConfigurationFailsClosed(Class<?> configuration) {
        new ApplicationContextRunner().withUserConfiguration(configuration)
                .withPropertyValues("fraud.source-lineage.enabled=true").run(context -> assertThat(context).hasFailed());
        new ApplicationContextRunner().withUserConfiguration(configuration)
                .withPropertyValues("fraud.source-lineage.enabled=true", "fraud.source-lineage.database=lineage",
                        "fraud.source-lineage.expected-mongo-user=scorer", "fraud.source-lineage.mongo-uri=mongodb://localhost:27017")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @Import(TransactionFraudScoringService.class)
    static class ScorerBeanConfiguration {
        @Bean FraudScoringEngine fraudScoringEngine() { return mock(FraudScoringEngine.class); }
        @Bean TransactionScoredEventMapper transactionScoredEventMapper() { return mock(TransactionScoredEventMapper.class); }
        @Bean TransactionScoredEventPublisher transactionScoredEventPublisher() { return mock(TransactionScoredEventPublisher.class); }
        @Bean ScoringProperties scoringProperties() { return new ScoringProperties(.75d, .90d, ScoringMode.RULE_BASED); }
        @Bean ScoringMetrics scoringMetrics() { return new ScoringMetrics(new SimpleMeterRegistry()); }
        @Bean EngineIntelligenceEmissionService engineIntelligenceEmissionService() { return mock(EngineIntelligenceEmissionService.class); }
        @Bean AnalystRecommendationService analystRecommendationService() { return new AnalystRecommendationService(); }
    }
}
