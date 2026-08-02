package com.frauddetection.scoring.engine.velocity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class VelocityThresholdOwnershipGuardTest {
    private static final Pattern RATE_THRESHOLD_COMPARISON = Pattern.compile(
            "(transactionVelocityPerMinute|TRANSACTION_VELOCITY_PER_MINUTE)[\\s\\S]{0,120}(>=|>|compareTo)\\s*\\(?\\s*5\\.0d?"
    );

    @Test
    void velocityRateClassificationThresholdIsOwnedOnlyByVelocityPolicy() throws Exception {
        Path root = repositoryRoot();
        List<Path> rootsToScan = List.of(
                root.resolve("feature-enricher-service/src/main/java"),
                root.resolve("common-events/src/main/java"),
                root.resolve("fraud-scoring-service/src/main/java"),
                root.resolve("alert-service/src/main/java"),
                root.resolve("analyst-console-ui/src"),
                root.resolve("ml-inference-service/app"),
                root.resolve("ml-inference-service/offline_evaluation")
        );

        String outsidePolicy = sourceFiles(rootsToScan);

        assertThat(outsidePolicy).doesNotContain("TRANSACTION_VELOCITY_PER_MINUTE_THRESHOLD");
        assertThat(RATE_THRESHOLD_COMPARISON.matcher(outsidePolicy).find()).isFalse();
    }

    @Test
    void velocityPolicyKeepsTheLocalRateThreshold() throws Exception {
        Path policy = repositoryRoot().resolve(
                "fraud-scoring-service/src/main/java/com/frauddetection/scoring/engine/velocity/VelocitySignalPolicy.java"
        );

        assertThat(Files.readString(policy))
                .contains("TRANSACTION_VELOCITY_PER_MINUTE_THRESHOLD = 5.0d");
    }

    private String sourceFiles(List<Path> roots) throws IOException {
        StringBuilder content = new StringBuilder();
        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(this::isRelevantSource)
                        .filter(this::isNotVelocityPolicy)
                        .toList()) {
                    content.append(Files.readString(file)).append('\n');
                }
            }
        }
        return content.toString();
    }

    private boolean isRelevantSource(Path path) {
        String filename = path.toString();
        return filename.endsWith(".java")
                || filename.endsWith(".js")
                || filename.endsWith(".jsx")
                || filename.endsWith(".py");
    }

    private boolean isNotVelocityPolicy(Path path) {
        return !path.endsWith(Path.of(
                "fraud-scoring-service",
                "src",
                "main",
                "java",
                "com",
                "frauddetection",
                "scoring",
                "engine",
                "velocity",
                "VelocitySignalPolicy.java"
        ));
    }

    private Path repositoryRoot() {
        Path current = Path.of(".").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve("common-events"))
                    && Files.exists(candidate.resolve("fraud-scoring-service"))
                    && Files.exists(candidate.resolve("analyst-console-ui"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not resolve repository root from " + current);
    }
}
