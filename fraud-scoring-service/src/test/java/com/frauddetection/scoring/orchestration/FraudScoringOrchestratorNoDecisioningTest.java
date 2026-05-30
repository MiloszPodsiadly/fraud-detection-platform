package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class FraudScoringOrchestratorNoDecisioningTest {

    @Test
    void orchestrationResultDoesNotExposeFinalDecisionFields() {
        assertThat(recordComponentNames(FraudScoringOrchestrationResult.class))
                .doesNotContain(
                        "finalDecision",
                        "approve",
                        "decline",
                        "block",
                        "recommendedAction",
                        "finalRisk",
                        "overallRisk",
                        "platformRisk",
                        "winningEngine",
                        "finalDecisionSource",
                        "primaryDecisionSource"
                );
        assertThat(methodNames(FraudScoringOrchestrationResult.class))
                .doesNotContain(
                        "finalDecision",
                        "approve",
                        "decline",
                        "block",
                        "recommendedAction",
                        "finalRisk",
                        "overallRisk",
                        "platformRisk",
                        "winningEngine",
                        "finalDecisionSource",
                        "primaryDecisionSource"
                );
    }

    @Test
    void orchestratorDoesNotExposeApproveDeclineMethods() {
        assertThat(methodNames(FraudScoringOrchestrator.class))
                .doesNotContain("approve", "decline", "decide", "block", "recommend", "finalizeDecision");
    }

    @Test
    void transactionScoredEventDoesNotExposeEngineResults() {
        assertThat(recordComponentNames(TransactionScoredEvent.class))
                .doesNotContain("engineResults", "orchestrationResult");
        assertThat(methodNames(TransactionScoredEvent.class))
                .doesNotContain("engineResults", "orchestrationResult");
    }

    @Test
    void compositeFraudScoringEngineDoesNotUseOrchestrator() throws Exception {
        String composite = Files.readString(repositoryRoot().resolve(
                "fraud-scoring-service/src/main/java/com/frauddetection/scoring/service/CompositeFraudScoringEngine.java"
        ));

        assertThat(composite)
                .doesNotContain("FraudScoringOrchestrator")
                .doesNotContain("FraudScoringOrchestrationResult");
    }

    private String[] recordComponentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .toArray(String[]::new);
    }

    private String[] methodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .map(Method::getName)
                .toArray(String[]::new);
    }

    private Path repositoryRoot() {
        Path current = Path.of(".").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve("fraud-scoring-service"))
                    && Files.exists(candidate.resolve("common-events"))
                    && Files.exists(candidate.resolve("alert-service"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not resolve repository root from " + current);
    }
}
