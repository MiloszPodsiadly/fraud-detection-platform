package com.frauddetection.scoring.engine;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class FraudSignalEnginePublicationMetadataBoundaryTest {

    @Test
    void engineAdaptersDoNotConstructPublishableFraudEngineResultsOrSentinelMetadata() throws Exception {
        String adapterSource = source(
                "src/main/java/com/frauddetection/scoring/engine/rules",
                "src/main/java/com/frauddetection/scoring/engine/ml",
                "src/main/java/com/frauddetection/scoring/engine/velocity"
        ).toLowerCase(Locale.ROOT);

        assertThat(adapterSource)
                .doesNotContain("new fraudengineresult")
                .doesNotContain("instant.epoch")
                .doesNotContain("receivedat()");
    }

    @Test
    void orchestratorOwnsPublicFraudEngineResultPublicationMetadata() throws Exception {
        String orchestrator = Files.readString(Path.of(
                "src/main/java/com/frauddetection/scoring/orchestration/FraudScoringOrchestrator.java"
        )).toLowerCase(Locale.ROOT);

        assertThat(orchestrator)
                .contains("new fraudengineresult")
                .contains("latency.tomillis()")
                .contains("generatedat");
    }

    private String source(String... roots) throws Exception {
        StringBuilder source = new StringBuilder();
        for (String root : roots) {
            try (var files = Files.walk(Path.of(root))) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .toList()) {
                    source.append(Files.readString(file)).append('\n');
                }
            }
        }
        return source.toString();
    }
}
