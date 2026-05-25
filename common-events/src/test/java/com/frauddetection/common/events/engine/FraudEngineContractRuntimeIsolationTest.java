package com.frauddetection.common.events.engine;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FraudEngineContractRuntimeIsolationTest {

    @Test
    void currentScoredTransactionEventDoesNotExposeEngineResults() {
        assertThat(Arrays.stream(TransactionScoredEvent.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("engineResults");
    }

    @Test
    void currentScoredTransactionEventDoesNotExposeFutureFraudIntelligenceFields() {
        assertThat(Arrays.stream(TransactionScoredEvent.class.getRecordComponents())
                .map(RecordComponent::getName))
                .withFailMessage("FDP-82 is contract-only. Event integration belongs to a later branch with explicit compatibility tests.")
                .doesNotContain(
                        "engineResults",
                        "platformRiskScore",
                        "platformRiskLevel",
                        "primarySource",
                        "finalDecisionSource",
                        "engineAgreement",
                        "engineDisagreementReason",
                        "recommendedAnalystAction",
                        "recommendedQueue",
                        "strongestSignals"
                );
    }

    @Test
    void scoringRuntimeDoesNotUseFoundationContractOrFutureOrchestrator() throws Exception {
        String scoringRuntime = javaSources(repositoryRoot().resolve("fraud-scoring-service/src/main/java"));

        assertThat(scoringRuntime)
                .doesNotContain("FraudEngineResult")
                .doesNotContain("FraudScoringOrchestrator")
                .doesNotContain("FraudSignalEngine")
                .doesNotContain("ScoringContext");
    }

    @Test
    void alertProjectionAndUiDoNotUseFoundationContract() throws Exception {
        String alertRuntime = javaSources(repositoryRoot().resolve("alert-service/src/main/java"));
        String uiRuntime = sourceFiles(repositoryRoot().resolve("analyst-console-ui/src"));

        assertThat(alertRuntime).doesNotContain("FraudEngineResult");
        assertThat(uiRuntime)
                .doesNotContain("FraudEngineResult")
                .doesNotContain("TransactionRiskIntelligence");
    }

    private String javaSources(Path root) throws IOException {
        return sourceFiles(root, ".java");
    }

    private String sourceFiles(Path root, String... suffixes) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            StringBuilder content = new StringBuilder();
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> endsWithAny(path, suffixes))
                    .toList()) {
                content.append(Files.readString(file)).append('\n');
            }
            return content.toString();
        }
    }

    private boolean endsWithAny(Path path, String... suffixes) {
        String filename = path.toString();
        if (suffixes.length == 0) {
            return filename.endsWith(".js") || filename.endsWith(".jsx");
        }
        return Arrays.stream(suffixes).anyMatch(filename::endsWith);
    }

    private Path repositoryRoot() {
        Path current = Path.of(".").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve("common-events"))
                    && Files.exists(candidate.resolve("fraud-scoring-service"))
                    && Files.exists(candidate.resolve("alert-service"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not resolve repository root from " + current);
    }
}
