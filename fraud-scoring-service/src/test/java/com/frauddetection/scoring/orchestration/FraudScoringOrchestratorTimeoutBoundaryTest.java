package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.availableResult;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.context;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.mlDescriptor;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.mlEngine;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.ruleDescriptor;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.ruleEngine;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.timeoutResult;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.warningCodes;
import static org.assertj.core.api.Assertions.assertThat;

class FraudScoringOrchestratorTimeoutBoundaryTest {

    @Test
    void orchestrationPackageDoesNotContainFakeTimeoutInfrastructure() throws Exception {
        String source = sourceFiles(Path.of("src/main/java/com/frauddetection/scoring/orchestration"));

        assertThat(source)
                .doesNotContain("Executor")
                .doesNotContain("ExecutorService")
                .doesNotContain("CompletableFuture")
                .doesNotContain("Future")
                .doesNotContain("Scheduler")
                .doesNotContain("ThreadPool")
                .doesNotContain("cancel")
                .doesNotContain("await")
                .doesNotContain("timeoutMillis")
                .doesNotContain("deadline")
                .doesNotContain("Duration.of")
                .doesNotContain("Instant.now()");
    }

    @Test
    void adapterReturnedTimeoutIsPreserved() {
        FraudScoringOrchestrationResult result = new FraudScoringOrchestrator(new FraudSignalEngineRegistry(List.of(
                ruleEngine(availableResult(ruleDescriptor(), 0.82d, RiskLevel.HIGH)),
                mlEngine(timeoutResult(mlDescriptor()))
        ))).evaluate(context());

        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.PARTIAL);
        assertThat(result.engineResults().get(1).status()).isEqualTo(FraudEngineStatus.TIMEOUT);
        assertThat(result.engineResults().get(1).riskLevel()).isNull();
        assertThat(result.engineResults().get(1).toString()).doesNotContain("LOW");
        assertThat(warningCodes(result)).contains(FraudScoringExecutionWarningCode.ENGINE_TIMEOUT_RECORDED);
    }

    @Test
    void timeoutDocsStateNoDeadlineEnforcement() throws Exception {
        String docs = Files.readString(Path.of("..", "docs", "architecture", "fraud_scoring_orchestrator.md"))
                .toLowerCase();

        assertThat(docs)
                .contains("fdp-89 does not enforce engine execution deadlines")
                .contains("a hanging engine can still block the caller")
                .contains("timeout enforcement belongs to fdp-90");
    }

    private String sourceFiles(Path root) throws Exception {
        try (Stream<Path> files = Files.walk(root)) {
            StringBuilder source = new StringBuilder();
            for (Path file : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                source.append(Files.readString(file)).append('\n');
            }
            return source.toString();
        }
    }
}
