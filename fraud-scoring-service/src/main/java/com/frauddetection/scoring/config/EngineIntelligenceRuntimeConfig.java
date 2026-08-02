package com.frauddetection.scoring.config;

import com.frauddetection.scoring.context.ScoringContextFactory;
import com.frauddetection.scoring.engine.ml.PythonMlSignalEngine;
import com.frauddetection.scoring.engine.rules.RuleBasedSignalEngine;
import com.frauddetection.scoring.engine.velocity.VelocitySignalEngine;
import com.frauddetection.scoring.features.FeatureSnapshotReaderFactory;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import com.frauddetection.scoring.orchestration.FraudSignalEngineRegistry;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceDiagnosticEnrichmentPipeline;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionMetrics;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionService;
import com.frauddetection.scoring.orchestration.aggregation.FraudEngineAggregationPolicy;
import com.frauddetection.scoring.orchestration.aggregation.FraudEngineAggregationService;
import com.frauddetection.scoring.orchestration.aggregation.OrchestratedEngineIntelligenceDiagnosticEnrichmentPipeline;
import com.frauddetection.scoring.orchestration.aggregation.NoOpEngineIntelligenceEmissionMetrics;
import com.frauddetection.scoring.orchestration.aggregation.PublicEngineIntelligenceMapper;
import com.frauddetection.scoring.orchestration.runtime.BoundedFraudEngineExecutor;
import com.frauddetection.scoring.orchestration.runtime.FraudScoringOrchestratorExecutionPolicy;
import com.frauddetection.scoring.orchestration.runtime.FraudScoringOrchestratorMetrics;
import com.frauddetection.scoring.orchestration.runtime.MicrometerFraudScoringOrchestratorMetrics;
import com.frauddetection.scoring.orchestration.runtime.MonotonicTicker;
import com.frauddetection.scoring.service.MlFraudScoringEngine;
import com.frauddetection.scoring.service.RuleBasedFraudScoringEngine;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        EngineIntelligenceEmissionProperties.class,
        VelocityEngineProperties.class
})
public class EngineIntelligenceRuntimeConfig {

    @Bean
    public EngineIntelligenceEmissionService engineIntelligenceEmissionService(
            EngineIntelligenceEmissionProperties properties,
            ObjectProvider<EngineIntelligenceDiagnosticEnrichmentPipeline> diagnosticPipeline,
            EngineIntelligenceEmissionMetrics metrics
    ) {
        return new EngineIntelligenceEmissionService(properties, diagnosticPipeline, metrics);
    }

    @Bean
    public EngineIntelligenceEmissionMetrics engineIntelligenceEmissionMetrics() {
        return new NoOpEngineIntelligenceEmissionMetrics();
    }

    @Bean
    public EngineIntelligenceRuntimeConfigurationValidator engineIntelligenceRuntimeConfigurationValidator(
            EngineIntelligenceEmissionProperties emissionProperties,
            VelocityEngineProperties velocityEngineProperties
    ) {
        return new EngineIntelligenceRuntimeConfigurationValidator(emissionProperties, velocityEngineProperties);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = EngineIntelligenceEmissionProperties.PROPERTY_NAME, havingValue = "true")
    static class EnabledDiagnosticRuntimeConfig {

        @Bean
        ScoringContextFactory scoringContextFactory() {
            return new ScoringContextFactory();
        }

        @Bean
        FeatureSnapshotReaderFactory featureSnapshotReaderFactory() {
            return new FeatureSnapshotReaderFactory();
        }

        @Bean
        RuleBasedSignalEngine ruleBasedSignalEngine(
                FeatureSnapshotReaderFactory featureSnapshotReaderFactory,
                RuleBasedFraudScoringEngine ruleBasedFraudScoringEngine
        ) {
            return new RuleBasedSignalEngine(featureSnapshotReaderFactory, ruleBasedFraudScoringEngine);
        }

        @Bean
        PythonMlSignalEngine pythonMlSignalEngine(MlFraudScoringEngine mlFraudScoringEngine) {
            return new PythonMlSignalEngine(mlFraudScoringEngine);
        }

        @Bean
        @ConditionalOnProperty(name = "fraud.scoring.engines.velocity.enabled", havingValue = "true")
        VelocitySignalEngine velocitySignalEngine(FeatureSnapshotReaderFactory featureSnapshotReaderFactory) {
            return new VelocitySignalEngine(featureSnapshotReaderFactory);
        }

        @Bean
        FraudSignalEngineRegistry fraudSignalEngineRegistry(
                RuleBasedSignalEngine ruleBasedSignalEngine,
                PythonMlSignalEngine pythonMlSignalEngine,
                ObjectProvider<VelocitySignalEngine> velocitySignalEngine
        ) {
            VelocitySignalEngine velocity = velocitySignalEngine.getIfAvailable();
            if (velocity == null) {
                return new FraudSignalEngineRegistry(List.of(ruleBasedSignalEngine, pythonMlSignalEngine));
            }
            return new FraudSignalEngineRegistry(List.of(ruleBasedSignalEngine, pythonMlSignalEngine, velocity));
        }

        @Bean(destroyMethod = "close")
        FraudScoringOrchestrator fraudScoringOrchestrator(
                FraudSignalEngineRegistry registry,
                FraudScoringOrchestratorExecutionPolicy executionPolicy,
                BoundedFraudEngineExecutor executor,
                FraudScoringOrchestratorMetrics metrics,
                Clock engineIntelligenceClock,
                MonotonicTicker engineIntelligenceTicker
        ) {
            return new FraudScoringOrchestrator(
                    registry,
                    executionPolicy,
                    executor,
                    metrics,
                    engineIntelligenceClock,
                    engineIntelligenceTicker
            );
        }

        @Bean
        FraudScoringOrchestratorExecutionPolicy fraudScoringOrchestratorExecutionPolicy() {
            return FraudScoringOrchestratorExecutionPolicy.defaultInternalPolicy();
        }

        @Bean
        BoundedFraudEngineExecutor boundedFraudEngineExecutor() {
            return BoundedFraudEngineExecutor.defaultInternalExecutor();
        }

        @Bean
        FraudScoringOrchestratorMetrics fraudScoringOrchestratorMetrics(MeterRegistry meterRegistry) {
            return new MicrometerFraudScoringOrchestratorMetrics(meterRegistry);
        }

        @Bean
        Clock engineIntelligenceClock() {
            return Clock.systemUTC();
        }

        @Bean
        MonotonicTicker engineIntelligenceTicker() {
            return MonotonicTicker.system();
        }

        @Bean
        FraudEngineAggregationService fraudEngineAggregationService() {
            return new FraudEngineAggregationService(FraudEngineAggregationPolicy.defaultInternalPolicy());
        }

        @Bean
        PublicEngineIntelligenceMapper publicEngineIntelligenceMapper() {
            return new PublicEngineIntelligenceMapper();
        }

        @Bean
        EngineIntelligenceDiagnosticEnrichmentPipeline engineIntelligenceDiagnosticEnrichmentPipeline(
                ScoringContextFactory scoringContextFactory,
                ScoringProperties scoringProperties,
                FraudScoringOrchestrator orchestrator,
                FraudEngineAggregationService aggregationService,
                PublicEngineIntelligenceMapper mapper,
                Clock engineIntelligenceClock
        ) {
            return new OrchestratedEngineIntelligenceDiagnosticEnrichmentPipeline(
                    scoringContextFactory,
                    scoringProperties,
                    orchestrator,
                    aggregationService,
                    mapper,
                    engineIntelligenceClock
            );
        }
    }
}
