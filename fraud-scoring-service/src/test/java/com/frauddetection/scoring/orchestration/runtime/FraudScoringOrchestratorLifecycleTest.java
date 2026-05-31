package com.frauddetection.scoring.orchestration.runtime;

import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static com.frauddetection.scoring.orchestration.runtime.RuntimeOrchestratorTestSupport.registry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class FraudScoringOrchestratorLifecycleTest {

    @Test
    void defaultConstructorCreatesCloseableOrchestrator() {
        FraudScoringOrchestrator orchestrator = new FraudScoringOrchestrator(registry());

        assertThatCode(orchestrator::close).doesNotThrowAnyException();
    }

    @Test
    void explicitExecutorIsClosedWhenOrchestratorCloses() {
        RuntimeOrchestratorTestSupport.ScriptedExecutorService executorService =
                new RuntimeOrchestratorTestSupport.ScriptedExecutorService(List.of());
        BoundedFraudEngineExecutor executor = new BoundedFraudEngineExecutor(executorService);
        FraudScoringOrchestrator orchestrator = new FraudScoringOrchestrator(
                registry(),
                RuntimeOrchestratorTestSupport.executionPolicy(),
                executor,
                new NoOpFraudScoringOrchestratorMetrics(),
                Clock.fixed(RuntimeOrchestratorTestSupport.RECEIVED_AT, ZoneOffset.UTC)
        );

        orchestrator.close();

        assertThat(executor.isShutdown()).isTrue();
    }

    @Test
    void docsDeclareDefaultExecutorOwnership() throws Exception {
        String docs = Files.readString(docsRoot().resolve("architecture/orchestrator_runtime_readiness.md"))
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");

        assertThat(docs)
                .contains("the owner of an orchestrator created this way must call `close()`")
                .contains("fdp-90 does not provide spring lifecycle management")
                .contains("future runtime wiring must inject an explicitly lifecycle-managed executor");
    }

    @Test
    void productionRuntimeMustNotUseDefaultOrchestratorConstructor() throws Exception {
        Path productionRoot = Path.of("src", "main", "java");
        Path orchestratorSource = productionRoot.resolve(
                "com/frauddetection/scoring/orchestration/FraudScoringOrchestrator.java"
        ).normalize();
        StringBuilder productionSources = new StringBuilder();
        try (Stream<Path> files = Files.walk(productionRoot)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> !path.normalize().equals(orchestratorSource))
                    .toList()) {
                productionSources.append(Files.readString(file)).append('\n');
            }
        }

        assertThat(productionSources.toString())
                .doesNotContainPattern("new\\s+FraudScoringOrchestrator\\s*\\(");
    }

    private Path docsRoot() {
        Path moduleRelative = Path.of("..", "docs");
        return Files.exists(moduleRelative) ? moduleRelative : Path.of("docs");
    }
}
