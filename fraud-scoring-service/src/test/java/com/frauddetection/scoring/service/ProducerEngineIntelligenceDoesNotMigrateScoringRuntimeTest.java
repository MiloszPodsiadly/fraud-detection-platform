package com.frauddetection.scoring.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProducerEngineIntelligenceDoesNotMigrateScoringRuntimeTest {

    @Test
    void liveScoringServiceKeepsExistingMapperCallAndDoesNotInvokeOrchestrator() throws Exception {
        String scoringService = Files.readString(moduleRoot().resolve(
                "src/main/java/com/frauddetection/scoring/service/TransactionFraudScoringService.java"
        ));

        assertThat(scoringService)
                .contains(".toEvent(scoringRequest, scoreResult)")
                .doesNotContain("FraudScoringOrchestrator", "EngineIntelligenceEmissionService");
    }

    @Test
    void compositeScoringEngineRemainsUnchangedByProducerCapability() throws Exception {
        assertThat(Files.readString(moduleRoot().resolve(
                "src/main/java/com/frauddetection/scoring/service/CompositeFraudScoringEngine.java"
        ))).doesNotContain(
                "FraudScoringOrchestrator",
                "EngineIntelligenceEmissionService",
                "EngineIntelligenceSummary"
        );
    }

    private Path moduleRoot() {
        Path current = Path.of(".").toAbsolutePath().normalize();
        return Files.exists(current.resolve("src/main"))
                ? current
                : current.resolve("fraud-scoring-service");
    }
}
