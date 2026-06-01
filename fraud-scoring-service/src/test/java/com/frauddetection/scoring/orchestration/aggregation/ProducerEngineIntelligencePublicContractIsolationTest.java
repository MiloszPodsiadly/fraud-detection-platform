package com.frauddetection.scoring.orchestration.aggregation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProducerEngineIntelligencePublicContractIsolationTest {

    @Test
    void sharedEventContractDoesNotImportInternalScoringTypes() throws Exception {
        assertThat(Files.readString(repositoryRoot().resolve(
                "common-events/src/main/java/com/frauddetection/common/events/contract/TransactionScoredEvent.java"
        ))).contains("EngineIntelligenceSummary")
                .doesNotContain(
                        "com.frauddetection.scoring",
                        "FraudEngineAggregationResult",
                        "FraudScoringOrchestrator"
                );
    }

    @Test
    void eventMapperAcceptsPublicSummaryWithoutInternalAggregationTypes() throws Exception {
        assertThat(Files.readString(repositoryRoot().resolve(
                "fraud-scoring-service/src/main/java/com/frauddetection/scoring/mapper/TransactionScoredEventMapper.java"
        ))).contains("Optional<EngineIntelligenceSummary>")
                .doesNotContain(
                        "FraudEngineAggregationResult",
                        "FraudScoringOrchestrator",
                        "PublicEngineIntelligenceMapper"
                );
    }

    private Path repositoryRoot() {
        Path current = Path.of(".").toAbsolutePath().normalize();
        return Files.exists(current.resolve("fraud-scoring-service"))
                ? current
                : current.getParent();
    }
}
