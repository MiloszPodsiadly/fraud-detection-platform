package com.frauddetection.scoring.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProducerEngineIntelligenceDoesNotMigrateScoringRuntimeTest {

    @Test
    void liveScoringServiceUsesOptionalDiagnosticEmissionAfterBaselineScoring() throws Exception {
        String scoringService = Files.readString(moduleRoot().resolve(
                "src/main/java/com/frauddetection/scoring/service/TransactionFraudScoringService.java"
        ));

        int baselineScoring = scoringService.indexOf(
                "FraudScoreResult scoreResult = fraudScoringEngine.score(scoringRequest);"
        );
        int diagnosticEmission = scoringService.indexOf(
                "EngineIntelligenceEmissionResult engineIntelligence = engineIntelligence(scoringRequest);"
        );

        assertThat(baselineScoring).isGreaterThanOrEqualTo(0);
        assertThat(diagnosticEmission).isGreaterThan(baselineScoring);
        assertThat(scoringService)
                .contains(
                        "engineIntelligenceEmissionService.emitIfEnabled(scoringRequest)",
                        "summary(engineIntelligence)",
                        "mlPredictionEvidence(engineIntelligence)",
                        "mlPredictionEvidenceOmissionReason(engineIntelligence)"
                )
                .doesNotContain("FraudScoringOrchestrator");
    }

    @Test
    void compositeScoringEngineRemainsUnchangedByProducerWiring() throws Exception {
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
