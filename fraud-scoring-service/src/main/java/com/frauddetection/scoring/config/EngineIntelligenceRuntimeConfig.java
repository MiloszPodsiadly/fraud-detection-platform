package com.frauddetection.scoring.config;

import com.frauddetection.scoring.context.ScoringContextFactory;
import com.frauddetection.scoring.engine.ml.PythonMlSignalEngine;
import com.frauddetection.scoring.engine.rules.RuleBasedSignalEngine;
import com.frauddetection.scoring.features.FeatureSnapshotReaderFactory;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import com.frauddetection.scoring.orchestration.FraudSignalEngineRegistry;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionService;
import com.frauddetection.scoring.orchestration.aggregation.FraudEngineAggregationPolicy;
import com.frauddetection.scoring.orchestration.aggregation.FraudEngineAggregationService;
import com.frauddetection.scoring.orchestration.aggregation.PublicEngineIntelligenceMapper;
import com.frauddetection.scoring.orchestration.runtime.BoundedFraudEngineExecutor;
import com.frauddetection.scoring.orchestration.runtime.FraudScoringOrchestratorExecutionPolicy;
import com.frauddetection.scoring.orchestration.runtime.FraudScoringOrchestratorMetrics;
import com.frauddetection.scoring.orchestration.runtime.NoOpFraudScoringOrchestratorMetrics;
import com.frauddetection.scoring.service.MlFraudScoringEngine;
import com.frauddetection.scoring.service.RuleBasedFraudScoringEngine;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EngineIntelligenceEmissionProperties.class)
public class EngineIntelligenceRuntimeConfig {

    @Bean
    public ScoringContextFactory scoringContextFactory() {
        return new ScoringContextFactory();
    }

    @Bean
    public FeatureSnapshotReaderFactory featureSnapshotReaderFactory() {
        return new FeatureSnapshotReaderFactory();
    }

    @Bean
    public RuleBasedSignalEngine ruleBasedSignalEngine(
            FeatureSnapshotReaderFactory featureSnapshotReaderFactory,
            RuleBasedFraudScoringEngine ruleBasedFraudScoringEngine
    ) {
        return new RuleBasedSignalEngine(featureSnapshotReaderFactory, ruleBasedFraudScoringEngine);
    }

    @Bean
    public PythonMlSignalEngine pythonMlSignalEngine(MlFraudScoringEngine mlFraudScoringEngine) {
        return new PythonMlSignalEngine(mlFraudScoringEngine);
    }

    @Bean
    public FraudSignalEngineRegistry fraudSignalEngineRegistry(
            RuleBasedSignalEngine ruleBasedSignalEngine,
            PythonMlSignalEngine pythonMlSignalEngine
    ) {
        return new FraudSignalEngineRegistry(List.of(ruleBasedSignalEngine, pythonMlSignalEngine));
    }

    @Bean(destroyMethod = "close")
    public FraudScoringOrchestrator fraudScoringOrchestrator(
            FraudSignalEngineRegistry registry,
            FraudScoringOrchestratorExecutionPolicy executionPolicy,
            BoundedFraudEngineExecutor executor,
            FraudScoringOrchestratorMetrics metrics,
            Clock engineIntelligenceClock
    ) {
        return new FraudScoringOrchestrator(registry, executionPolicy, executor, metrics, engineIntelligenceClock);
    }

    @Bean
    public FraudScoringOrchestratorExecutionPolicy fraudScoringOrchestratorExecutionPolicy() {
        return FraudScoringOrchestratorExecutionPolicy.defaultInternalPolicy();
    }

    @Bean
    public BoundedFraudEngineExecutor boundedFraudEngineExecutor() {
        return BoundedFraudEngineExecutor.defaultInternalExecutor();
    }

    @Bean
    public FraudScoringOrchestratorMetrics fraudScoringOrchestratorMetrics() {
        return new NoOpFraudScoringOrchestratorMetrics();
    }

    @Bean
    public Clock engineIntelligenceClock() {
        return Clock.systemUTC();
    }

    @Bean
    public FraudEngineAggregationService fraudEngineAggregationService() {
        return new FraudEngineAggregationService(FraudEngineAggregationPolicy.defaultInternalPolicy());
    }

    @Bean
    public PublicEngineIntelligenceMapper publicEngineIntelligenceMapper() {
        return new PublicEngineIntelligenceMapper();
    }

    @Bean
    public EngineIntelligenceEmissionService engineIntelligenceEmissionService(
            EngineIntelligenceEmissionProperties properties,
            ScoringContextFactory scoringContextFactory,
            ScoringProperties scoringProperties,
            FraudScoringOrchestrator orchestrator,
            FraudEngineAggregationService aggregationService,
            PublicEngineIntelligenceMapper mapper
    ) {
        return new EngineIntelligenceEmissionService(
                properties,
                scoringContextFactory,
                scoringProperties,
                orchestrator,
                aggregationService,
                mapper
        );
    }
}
