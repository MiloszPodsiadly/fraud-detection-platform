package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceProducerEmissionNotWiredTest {

    private static final String MESSAGE = "ENGINE_INTELLIGENCE_PRODUCER_EMISSION_OUT_OF_SCOPE";

    @Test
    void runtimeProducerPathDoesNotPopulateOrMapEngineIntelligence() throws Exception {
        String eventMapper = EngineIntelligenceFdp93SourceScanSupport.read(
                "fraud-scoring-service/src/main/java/com/frauddetection/scoring/mapper/TransactionScoredEventMapper.java"
        );
        String publisher = EngineIntelligenceFdp93SourceScanSupport.read(
                "fraud-scoring-service/src/main/java/com/frauddetection/scoring/messaging/KafkaTransactionScoredEventPublisher.java"
        );
        String scoringService = EngineIntelligenceFdp93SourceScanSupport.read(
                "fraud-scoring-service/src/main/java/com/frauddetection/scoring/service/TransactionFraudScoringService.java"
        );

        assertThat(eventMapper + publisher + scoringService)
                .withFailMessage(MESSAGE)
                .doesNotContain("new EngineIntelligenceSummary", "PublicEngineIntelligenceMapper", "engineIntelligence");
        assertThat(eventMapper).contains("scoreResult.scoringEvidence()");
    }

    @Test
    void compositeScoringEngineDoesNotReferenceEngineIntelligence() throws Exception {
        assertThat(EngineIntelligenceFdp93SourceScanSupport.read(
                "fraud-scoring-service/src/main/java/com/frauddetection/scoring/service/CompositeFraudScoringEngine.java"
        )).withFailMessage(MESSAGE)
                .doesNotContain("EngineIntelligenceSummary", "PublicEngineIntelligenceMapper", "engineIntelligence");
    }

    @Test
    void noConfigurationEnablesEngineIntelligenceEmission() throws Exception {
        assertThat(EngineIntelligenceFdp93SourceScanSupport.sources("fraud-scoring-service/src/main/resources"))
                .withFailMessage(MESSAGE)
                .doesNotContain("engine-intelligence", "engineIntelligence", "ENGINE_INTELLIGENCE");
    }
}
