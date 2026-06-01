package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceProducerEmissionScopeTest {

    private static final String MESSAGE = "ENGINE_INTELLIGENCE_PRODUCER_SCOPE_VIOLATION";

    @Test
    void mapperCapabilityUsesOptionalPublicSummaryOnly() throws Exception {
        String mapper = EngineIntelligenceFdp93SourceScanSupport.read(
                "fraud-scoring-service/src/main/java/com/frauddetection/scoring/mapper/TransactionScoredEventMapper.java"
        );

        assertThat(mapper)
                .withFailMessage(MESSAGE)
                .contains("Optional<EngineIntelligenceSummary>", "engineIntelligence.orElse(null)")
                .doesNotContain(
                        "FraudEngineAggregationResult",
                        "FraudScoringOrchestrator",
                        "PublicEngineIntelligenceMapper"
                );
    }

    @Test
    void liveProducerPathUsesOptionalEmissionBoundaryWithoutDirectOrchestratorAccess() throws Exception {
        String publisher = EngineIntelligenceFdp93SourceScanSupport.read(
                "fraud-scoring-service/src/main/java/com/frauddetection/scoring/messaging/KafkaTransactionScoredEventPublisher.java"
        );
        String scoringService = EngineIntelligenceFdp93SourceScanSupport.read(
                "fraud-scoring-service/src/main/java/com/frauddetection/scoring/service/TransactionFraudScoringService.java"
        );

        assertThat(publisher)
                .withFailMessage(MESSAGE)
                .doesNotContain(
                        "EngineIntelligenceSummary",
                        "EngineIntelligenceEmissionService",
                        "FraudScoringOrchestrator",
                        "engineIntelligence"
                );
        assertThat(scoringService)
                .withFailMessage(MESSAGE)
                .contains(
                        "EngineIntelligenceEmissionService",
                        "engineIntelligenceEmissionService.emitIfEnabled(scoringRequest)",
                        "Optional<EngineIntelligenceSummary>",
                        "scoreResult,",
                        "engineIntelligence"
                )
                .doesNotContain("FraudScoringOrchestrator");
    }

    @Test
    void compositeScoringEngineDoesNotReferenceEngineIntelligence() throws Exception {
        assertThat(EngineIntelligenceFdp93SourceScanSupport.read(
                "fraud-scoring-service/src/main/java/com/frauddetection/scoring/service/CompositeFraudScoringEngine.java"
        )).withFailMessage(MESSAGE)
                .doesNotContain(
                        "EngineIntelligenceSummary",
                        "EngineIntelligenceEmissionService",
                        "PublicEngineIntelligenceMapper",
                        "FraudScoringOrchestrator",
                        "engineIntelligence"
                );
    }

    @Test
    void productionConfigDefinesOnlySpecificDisabledByDefaultEmissionFlag() throws Exception {
        String configuration = EngineIntelligenceFdp93SourceScanSupport.productionConfigurationSources();

        assertThat(configuration)
                .withFailMessage(MESSAGE)
                .contains(
                        "engine-intelligence:",
                        "emit-enabled: ${FRAUD_SCORING_EVENTS_ENGINE_INTELLIGENCE_EMIT_ENABLED:false}"
                )
                .doesNotContain(
                        "emitEngineIntelligence",
                        "emit-engine-intelligence",
                        "engineIntelligenceEnabled",
                        "engine-intelligence-enabled",
                        "publicEngineIntelligence",
                        "public-engine-intelligence"
                );
    }
}
