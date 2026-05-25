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
                        "scoringContext",
                        "engineResults",
                        "featureSnapshotReader",
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
    void scoringRuntimeDoesNotContainFutureImplementationsOrOrchestration() throws Exception {
        String scoringRuntime = javaSources(repositoryRoot().resolve("fraud-scoring-service/src/main/java"));

        assertThat(scoringRuntime)
                .doesNotContain("FraudScoringOrchestrator")
                .doesNotContain("RuleBasedSignalEngine")
                .doesNotContain("PythonMlSignalEngine")
                .doesNotContain("VelocitySignalEngine")
                .doesNotContain("DeviceSignalEngine")
                .doesNotContain("MerchantSignalEngine")
                .doesNotContain("ExperimentalSignalEngine")
                .doesNotContain("implements FraudSignalEngine")
                .doesNotContain("FraudIntelligenceResult")
                .doesNotContain("engineResults");
    }

    @Test
    void scoringEnginePackageContainsOnlyFdp84FoundationTypes() throws Exception {
        Path engineRoot = repositoryRoot().resolve(
                "fraud-scoring-service/src/main/java/com/frauddetection/scoring/engine"
        );

        try (Stream<Path> files = Files.walk(engineRoot)) {
            assertThat(files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(path -> engineRoot.relativize(path).toString().replace('\\', '/'))
                    .toList())
                    .containsExactlyInAnyOrder(
                            "FraudSignalEngine.java",
                            "FraudEngineDescriptor.java",
                            "FraudEngineDescriptorValuePolicy.java"
                    );
        }
    }

    @Test
    void existingScoringRuntimeDoesNotWireSignalEngineFoundation() throws Exception {
        Path scoringRoot = repositoryRoot().resolve("fraud-scoring-service/src/main/java/com/frauddetection/scoring");
        String existingScoringRuntime = javaSourcesExcept(scoringRoot, scoringRoot.resolve("engine"));
        String compositeRuntime = Files.readString(scoringRoot.resolve("service/CompositeFraudScoringEngine.java"));

        assertThat(existingScoringRuntime)
                .doesNotContain("FraudSignalEngine")
                .doesNotContain("FraudEngineDescriptor")
                .doesNotContain("FraudEngineResult")
                .doesNotContain("engineResults");
        assertThat(compositeRuntime)
                .doesNotContain("FeatureSnapshotReader")
                .doesNotContain("FeatureSnapshotReaderFactory")
                .doesNotContain("FeatureSnapshotValue")
                .doesNotContain("FraudSignalEngine")
                .doesNotContain("FraudEngineDescriptor")
                .doesNotContain("FraudEngineResult")
                .doesNotContain("FraudScoringOrchestrator")
                .doesNotContain("engineResults");
    }

    @Test
    void featureSnapshotConsumptionPolicyRemainsInternalAndUnwired() throws Exception {
        Path scoringRoot = repositoryRoot().resolve("fraud-scoring-service/src/main/java/com/frauddetection/scoring");
        Path featuresRoot = scoringRoot.resolve("features");
        String features = javaSources(featuresRoot);
        String runtimeOutsidePolicy = javaSourcesExcept(scoringRoot, featuresRoot);
        String adapterFoundation = javaSources(scoringRoot.resolve("engine"));

        assertThat(features)
                .contains("enum FeatureSnapshotValueStatus")
                .contains("record FeatureSnapshotValue")
                .contains("class FeatureSnapshotKeyPolicy")
                .contains("class FeatureSnapshotReader")
                .contains("class FeatureSnapshotReaderFactory");
        assertThat(runtimeOutsidePolicy)
                .doesNotContain("FeatureSnapshotReader")
                .doesNotContain("FeatureSnapshotValue")
                .doesNotContain("FeatureSnapshotValueStatus")
                .doesNotContain("FeatureSnapshotKeyPolicy")
                .doesNotContain("FeatureSnapshotReaderFactory");
        assertThat(adapterFoundation)
                .doesNotContain("FeatureSnapshotReader")
                .doesNotContain("context.featureSnapshot().get(")
                .doesNotContain("featureSnapshot().get(");
    }

    @Test
    void interfaceAndContextFoundationsRemainInternalToFraudScoringService() throws Exception {
        String scoringRuntime = javaSources(repositoryRoot().resolve("fraud-scoring-service/src/main/java"));
        String commonEventsRuntime = javaSources(repositoryRoot().resolve("common-events/src/main/java"));
        String alertRuntime = javaSources(repositoryRoot().resolve("alert-service/src/main/java"));
        String uiRuntime = sourceFiles(repositoryRoot().resolve("analyst-console-ui/src"));

        assertThat(scoringRuntime)
                .contains("record ScoringContext")
                .contains("interface FraudSignalEngine")
                .contains("record FraudEngineDescriptor");
        assertThat(commonEventsRuntime)
                .doesNotContain("ScoringContext")
                .doesNotContain("ScoringContextFactory")
                .doesNotContain("ScoringContextValuePolicy")
                .doesNotContain("FraudSignalEngine")
                .doesNotContain("FraudEngineDescriptor")
                .doesNotContain("FraudEngineDescriptorValuePolicy")
                .doesNotContain("FeatureSnapshotReader")
                .doesNotContain("FeatureSnapshotValue")
                .doesNotContain("FeatureSnapshotValueStatus")
                .doesNotContain("FeatureSnapshotKeyPolicy")
                .doesNotContain("FeatureSnapshotReaderFactory");
        assertThat(alertRuntime)
                .doesNotContain("ScoringContext")
                .doesNotContain("FeatureSnapshotReader")
                .doesNotContain("FeatureSnapshotValue")
                .doesNotContain("FraudSignalEngine")
                .doesNotContain("FraudEngineDescriptor")
                .doesNotContain("FraudEngineResult")
                .doesNotContain("engineResults");
        assertThat(uiRuntime)
                .doesNotContain("ScoringContext")
                .doesNotContain("FeatureSnapshotReader")
                .doesNotContain("FeatureSnapshotValue")
                .doesNotContain("FraudSignalEngine")
                .doesNotContain("FraudEngineDescriptor")
                .doesNotContain("FraudEngineResult")
                .doesNotContain("TransactionRiskIntelligence")
                .doesNotContain("engineResults");
    }

    private String javaSources(Path root) throws IOException {
        return sourceFiles(root, ".java");
    }

    private String javaSourcesExcept(Path root, Path excludedRoot) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            StringBuilder content = new StringBuilder();
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.startsWith(excludedRoot))
                    .toList()) {
                content.append(Files.readString(file)).append('\n');
            }
            return content.toString();
        }
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
