package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceDownstreamIsolationTest {

    @Test
    void alertServiceConsumesEngineIntelligenceOnlyThroughInternalProjectionBoundary() throws Exception {
        Path alertService = repositoryRoot().resolve("alert-service/src/main/java/com/frauddetection/alert");
        assertThat(sources(alertService.resolve("engineintelligence")))
                .contains("EngineIntelligenceSummary", "engineIntelligence");
        assertThat(sourcesExcluding(
                alertService,
                alertService.resolve("engineintelligence"),
                alertService.resolve("service/TransactionMonitoringService.java")
        )).doesNotContain("EngineIntelligenceSummary", "engineIntelligence");
    }

    @Test
    void apiAndUiDoNotExposeEngineIntelligence() throws Exception {
        Path root = repositoryRoot();
        assertThat(sources(root.resolve("alert-service/src/main/java/com/frauddetection/alert/controller")))
                .doesNotContain("EngineIntelligenceSummary", "engineIntelligence");
        assertThat(EngineIntelligenceFdp93SourceScanSupport.filesContainingAny(
                "analyst-console-ui/src",
                List.of("engineIntelligence")
        )).isSubsetOf(
                EngineIntelligenceFdp93SourceScanSupport.FDP97_ANALYST_CONSOLE_ENGINE_INTELLIGENCE_ALLOWED_FILES
        );
    }

    @Test
    void feedbackWorkflowDoesNotReferenceEngineIntelligence() throws Exception {
        assertThat(sources(repositoryRoot().resolve("alert-service/src/main/java/com/frauddetection/alert/mapper")))
                .doesNotContain("EngineIntelligenceSummary", "engineIntelligence");
    }

    @Test
    void compositeScoringEngineDoesNotReferenceEngineIntelligence() throws Exception {
        assertThat(Files.readString(repositoryRoot().resolve(
                "fraud-scoring-service/src/main/java/com/frauddetection/scoring/service/CompositeFraudScoringEngine.java"
        ))).doesNotContain("EngineIntelligenceSummary", "engineIntelligence");
    }

    private String sources(Path root) throws Exception {
        if (!Files.exists(root)) {
            return "";
        }
        try (Stream<Path> files = Files.walk(root)) {
            StringBuilder content = new StringBuilder();
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                content.append(Files.readString(file)).append('\n');
            }
            return content.toString();
        }
    }

    private String sourcesExcluding(Path root, Path... excluded) throws Exception {
        if (!Files.exists(root)) {
            return "";
        }
        try (Stream<Path> files = Files.walk(root)) {
            StringBuilder content = new StringBuilder();
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (Stream.of(excluded).noneMatch(file::startsWith)) {
                    content.append(Files.readString(file)).append('\n');
                }
            }
            return content.toString();
        }
    }

    private Path repositoryRoot() {
        Path current = Path.of(".").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve("common-events")) && Files.exists(candidate.resolve("alert-service"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not resolve repository root from " + current);
    }
}
