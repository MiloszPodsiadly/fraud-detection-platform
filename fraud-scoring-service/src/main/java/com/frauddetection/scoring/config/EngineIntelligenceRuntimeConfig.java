package com.frauddetection.scoring.config;

import com.frauddetection.scoring.context.ScoringContextFactory;
import com.frauddetection.scoring.engine.ml.PythonMlSignalEngine;
import com.frauddetection.scoring.engine.rules.RuleBasedSignalEngine;
import com.frauddetection.scoring.features.FeatureSnapshotReaderFactory;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import com.frauddetection.scoring.orchestration.FraudSignalEngineRegistry;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceDiagnosticEnrichmentPipeline;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionService;
import com.frauddetection.scoring.orchestration.aggregation.FraudEngineAggregationPolicy;
import com.frauddetection.scoring.orchestration.aggregation.FraudEngineAggregationService;
import com.frauddetection.scoring.orchestration.aggregation.OrchestratedEngineIntelligenceDiagnosticEnrichmentPipeline;
import com.frauddetection.scoring.orchestration.aggregation.PublicEngineIntelligenceMapper;
import com.frauddetection.scoring.orchestration.runtime.BoundedFraudEngineExecutor;
import com.frauddetection.scoring.orchestration.runtime.FraudScoringOrchestratorExecutionPolicy;
import com.frauddetection.scoring.orchestration.runtime.FraudScoringOrchestratorMetrics;
import com.frauddetection.scoring.orchestration.runtime.NoOpFraudScoringOrchestratorMetrics;
import com.frauddetection.scoring.service.MlFraudScoringEngine;
import com.frauddetection.scoring.service.RuleBasedFraudScoringEngine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EngineIntelligenceEmissionProperties.class)
public class EngineIntelligenceRuntimeConfig {

    @Bean
    public EngineIntelligenceEmissionService engineIntelligenceEmissionService(
            EngineIntelligenceEmissionProperties properties,
            ObjectProvider<EngineIntelligenceDiagnosticEnrichmentPipeline> diagnosticPipeline
    ) {
        return new EngineIntelligenceEmissionService(properties, diagnosticPipeline);
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
        FraudSignalEngineRegistry fraudSignalEngineRegistry(
                RuleBasedSignalEngine ruleBasedSignalEngine,
                PythonMlSignalEngine pythonMlSignalEngine
        ) {
            return new FraudSignalEngineRegistry(List.of(ruleBasedSignalEngine, pythonMlSignalEngine));
        }

        @Bean(destroyMethod = "close")
        FraudScoringOrchestrator fraudScoringOrchestrator(
                FraudSignalEngineRegistry registry,
                FraudScoringOrchestratorExecutionPolicy executionPolicy,
                BoundedFraudEngineExecutor executor,
                FraudScoringOrchestratorMetrics metrics,
                Clock engineIntelligenceClock
        ) {
            return new FraudScoringOrchestrator(registry, executionPolicy, executor, metrics, engineIntelligenceClock);
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
        FraudScoringOrchestratorMetrics fraudScoringOrchestratorMetrics() {
            return new NoOpFraudScoringOrchestratorMetrics();
        }

        @Bean
        Clock engineIntelligenceClock() {
            return Clock.systemUTC();
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
