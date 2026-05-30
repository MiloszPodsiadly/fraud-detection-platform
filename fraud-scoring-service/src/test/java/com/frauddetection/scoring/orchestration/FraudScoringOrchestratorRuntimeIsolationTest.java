package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FraudScoringOrchestratorRuntimeIsolationTest {

    @Test
    void orchestratorExistsOnlyInFraudScoringInternalPackage() throws Exception {
        Path repositoryRoot = repositoryRoot();
        String commonEvents = javaSources(repositoryRoot.resolve("common-events/src/main/java"));
        String alertService = javaSources(repositoryRoot.resolve("alert-service/src/main/java"));
        String ui = sourceFiles(repositoryRoot.resolve("analyst-console-ui/src"));

        assertThat(repositoryRoot.resolve(
                "fraud-scoring-service/src/main/java/com/frauddetection/scoring/orchestration/FraudScoringOrchestrator.java"
        )).exists();
        assertThat(repositoryRoot.resolve(
                "fraud-scoring-service/src/main/java/com/frauddetection/scoring/orchestration/FraudScoringOrchestrationResult.java"
        )).exists();
        assertThat(commonEvents)
                .doesNotContain("FraudScoringOrchestrator")
                .doesNotContain("FraudScoringOrchestrationResult")
                .doesNotContain("engineResults");
        assertThat(alertService)
                .doesNotContain("FraudScoringOrchestrator")
                .doesNotContain("FraudScoringOrchestrationResult")
                .doesNotContain("FraudEngineResult")
                .doesNotContain("engineResults");
        assertThat(ui)
                .doesNotContain("FraudScoringOrchestrator")
                .doesNotContain("FraudScoringOrchestrationResult")
                .doesNotContain("FraudEngineResult")
                .doesNotContain("engineResults");
    }

    @Test
    void transactionScoredEventDoesNotContainOrchestrationFields() {
        assertThat(Arrays.stream(TransactionScoredEvent.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain(
                        "engineResults",
                        "orchestrationResult",
                        "finalDecisionSource",
                        "platformRiskScore",
                        "platformRiskLevel",
                        "engineAgreement",
                        "recommendedAnalystAction",
                        "strongestSignals"
                );
    }

    @Test
    void compositeRuntimeDoesNotReferenceOrchestrator() throws Exception {
        String composite = Files.readString(repositoryRoot().resolve(
                "fraud-scoring-service/src/main/java/com/frauddetection/scoring/service/CompositeFraudScoringEngine.java"
        ));

        assertThat(composite)
                .doesNotContain("FraudScoringOrchestrator")
                .doesNotContain("FraudScoringOrchestrationResult");
    }

    @Test
    void orchestratorDoesNotUseExternalRuntimeChannels() throws Exception {
        String orchestrator = Files.readString(repositoryRoot().resolve(
                "fraud-scoring-service/src/main/java/com/frauddetection/scoring/orchestration/FraudScoringOrchestrator.java"
        ));

        assertThat(orchestrator)
                .doesNotContain("@Component")
                .doesNotContain("@Service")
                .doesNotContain("@Bean")
                .doesNotContain("@Configuration")
                .doesNotContain("KafkaTemplate")
                .doesNotContain("RestTemplate")
                .doesNotContain("WebClient")
                .doesNotContain("alert-service")
                .doesNotContain("Controller")
                .doesNotContain("Repository")
                .doesNotContain("RuleBasedFraudScoringEngine")
                .doesNotContain("MlFraudScoringEngine");
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
            if (Files.exists(candidate.resolve("fraud-scoring-service"))
                    && Files.exists(candidate.resolve("common-events"))
                    && Files.exists(candidate.resolve("alert-service"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not resolve repository root from " + current);
    }
}
