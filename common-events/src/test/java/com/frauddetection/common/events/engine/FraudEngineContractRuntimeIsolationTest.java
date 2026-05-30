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
        Path scoringRoot = repositoryRoot().resolve("fraud-scoring-service/src/main/java/com/frauddetection/scoring");
        String scoringRuntime = javaSourcesExcept(
                scoringRoot,
                scoringRoot.resolve("engine/rules"),
                scoringRoot.resolve("engine/ml")
        );

        assertThat(scoringRuntime)
                .doesNotContain("FraudScoringOrchestrator")
                .doesNotContain("VelocitySignalEngine")
                .doesNotContain("DeviceSignalEngine")
                .doesNotContain("MerchantSignalEngine")
                .doesNotContain("ExperimentalSignalEngine")
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
                            "FraudEngineDescriptorValuePolicy.java",
                            "ml/PythonMlSignalEngine.java",
                            "ml/PythonMlSignalReasonCode.java",
                            "rules/RuleBasedSignalEngine.java",
                            "rules/RuleBasedSignalReasonCode.java"
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
                .doesNotContain("FeatureSnapshotScalarType")
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
        Path engineRoot = scoringRoot.resolve("engine");
        String features = javaSources(featuresRoot);
        String runtimeOutsidePolicy = javaSourcesExcept(scoringRoot, featuresRoot, engineRoot);
        String adapterFoundation = javaSources(engineRoot);

        assertThat(features)
                .contains("enum FeatureSnapshotValueStatus")
                .contains("record FeatureSnapshotValue")
                .contains("enum FeatureSnapshotScalarType")
                .contains("class FeatureSnapshotKeyPolicy")
                .contains("class FeatureSnapshotReader")
                .contains("class FeatureSnapshotReaderFactory");
        assertThat(runtimeOutsidePolicy)
                .doesNotContain("FeatureSnapshotReader")
                .doesNotContain("FeatureSnapshotValue")
                .doesNotContain("FeatureSnapshotValueStatus")
                .doesNotContain("FeatureSnapshotScalarType")
                .doesNotContain("FeatureSnapshotKeyPolicy")
                .doesNotContain("FeatureSnapshotReaderFactory");
        assertThat(adapterFoundation)
                .doesNotContain("FeatureSnapshotScalarType")
                .doesNotContain("context.featureSnapshot().get(")
                .doesNotContain("featureSnapshot().get(");
    }

    @Test
    void ruleBasedSignalEngineRemainsInternalAdapterOnly() throws Exception {
        Path repositoryRoot = repositoryRoot();
        Path scoringRoot = repositoryRoot.resolve("fraud-scoring-service/src/main/java/com/frauddetection/scoring");
        Path adapterPath = scoringRoot.resolve("engine/rules/RuleBasedSignalEngine.java");
        String adapter = Files.readString(adapterPath);
        String runtimeOutsideAdapterPackage = javaSourcesExcept(
                scoringRoot,
                scoringRoot.resolve("engine/rules"),
                scoringRoot.resolve("engine/ml")
        );
        String compositeRuntime = Files.readString(scoringRoot.resolve("service/CompositeFraudScoringEngine.java"));
        String ruleRuntime = Files.readString(scoringRoot.resolve("service/RuleBasedFraudScoringEngine.java"));
        String mlRuntime = Files.readString(scoringRoot.resolve("service/MlFraudScoringEngine.java"));
        String alertRuntime = javaSources(repositoryRoot.resolve("alert-service/src/main/java"));
        String uiRuntime = sourceFiles(repositoryRoot.resolve("analyst-console-ui/src"));

        assertThat(adapterPath).exists();
        assertThat(adapter)
                .contains("public final class RuleBasedSignalEngine implements FraudSignalEngine")
                .contains("FeatureSnapshotReaderFactory")
                .doesNotContain("@Component")
                .doesNotContain("@Service")
                .doesNotContain("@Bean")
                .doesNotContain("@Configuration")
                .doesNotContain("context.featureSnapshot().get(")
                .doesNotContain("featureSnapshot().get(")
                .doesNotContain("Map<String, Object>")
                .doesNotContain("FeatureSnapshotKeyPolicy.isAllowedFeatureKey");

        assertThat(runtimeOutsideAdapterPackage)
                .doesNotContain("RuleBasedSignalEngine")
                .doesNotContain("PythonMlSignalEngine")
                .doesNotContain("FraudScoringOrchestrator")
                .doesNotContain("FraudIntelligenceResult")
                .doesNotContain("engineResults[]");
        assertThat(compositeRuntime)
                .doesNotContain("RuleBasedSignalEngine")
                .doesNotContain("FraudSignalEngine")
                .doesNotContain("FraudScoringOrchestrator")
                .doesNotContain("FraudIntelligenceResult")
                .doesNotContain("engineResults");
        assertThat(ruleRuntime).doesNotContain("RuleBasedSignalEngine");
        assertThat(mlRuntime).doesNotContain("RuleBasedSignalEngine");
        assertThat(alertRuntime)
                .doesNotContain("RuleBasedSignalEngine")
                .doesNotContain("FraudSignalEngine")
                .doesNotContain("FraudEngineResult")
                .doesNotContain("engineResults");
        assertThat(uiRuntime)
                .doesNotContain("RuleBasedSignalEngine")
                .doesNotContain("FraudEngineResult")
                .doesNotContain("engineResults")
                .doesNotContain("TransactionRiskIntelligence");
    }

    @Test
    void pythonMlSignalEngineRemainsInternalAdapterOnly() throws Exception {
        Path repositoryRoot = repositoryRoot();
        Path scoringRoot = repositoryRoot.resolve("fraud-scoring-service/src/main/java/com/frauddetection/scoring");
        Path adapterPath = scoringRoot.resolve("engine/ml/PythonMlSignalEngine.java");
        String adapter = Files.readString(adapterPath);
        String runtimeOutsideAdapterPackage = javaSourcesExcept(
                scoringRoot,
                scoringRoot.resolve("engine/rules"),
                scoringRoot.resolve("engine/ml")
        );
        String compositeRuntime = Files.readString(scoringRoot.resolve("service/CompositeFraudScoringEngine.java"));
        String ruleRuntime = Files.readString(scoringRoot.resolve("service/RuleBasedFraudScoringEngine.java"));
        String mlRuntime = Files.readString(scoringRoot.resolve("service/MlFraudScoringEngine.java"));
        String alertRuntime = javaSources(repositoryRoot.resolve("alert-service/src/main/java"));
        String uiRuntime = sourceFiles(repositoryRoot.resolve("analyst-console-ui/src"));

        assertThat(adapterPath).exists();
        assertThat(adapter)
                .contains("public final class PythonMlSignalEngine implements FraudSignalEngine")
                .contains("MlFraudScoringEngine")
                .doesNotContain("@Component")
                .doesNotContain("@Service")
                .doesNotContain("@Bean")
                .doesNotContain("@Configuration")
                .doesNotContain("context.featureSnapshot().get(")
                .doesNotContain("featureSnapshot().get(")
                .doesNotContain("Map<String, Object>")
                .doesNotContain("FeatureSnapshotKeyPolicy.isAllowedFeatureKey")
                .doesNotContain("exception.getMessage()")
                .doesNotContain("raw response body");

        assertThat(runtimeOutsideAdapterPackage)
                .doesNotContain("PythonMlSignalEngine")
                .doesNotContain("RuleBasedSignalEngine")
                .doesNotContain("FraudScoringOrchestrator")
                .doesNotContain("FraudIntelligenceResult")
                .doesNotContain("engineResults[]");
        assertThat(compositeRuntime)
                .doesNotContain("PythonMlSignalEngine")
                .doesNotContain("RuleBasedSignalEngine")
                .doesNotContain("FraudSignalEngine")
                .doesNotContain("FraudScoringOrchestrator")
                .doesNotContain("FraudIntelligenceResult")
                .doesNotContain("engineResults");
        assertThat(ruleRuntime)
                .doesNotContain("PythonMlSignalEngine")
                .doesNotContain("RuleBasedSignalEngine");
        assertThat(mlRuntime)
                .doesNotContain("PythonMlSignalEngine")
                .doesNotContain("RuleBasedSignalEngine");
        assertThat(alertRuntime)
                .doesNotContain("PythonMlSignalEngine")
                .doesNotContain("RuleBasedSignalEngine")
                .doesNotContain("FraudSignalEngine")
                .doesNotContain("FraudEngineResult")
                .doesNotContain("engineResults");
        assertThat(uiRuntime)
                .doesNotContain("PythonMlSignalEngine")
                .doesNotContain("RuleBasedSignalEngine")
                .doesNotContain("FraudEngineResult")
                .doesNotContain("engineResults")
                .doesNotContain("TransactionRiskIntelligence");
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
                .doesNotContain("FeatureSnapshotScalarType")
                .doesNotContain("FeatureSnapshotKeyPolicy")
                .doesNotContain("FeatureSnapshotReaderFactory");
        assertThat(alertRuntime)
                .doesNotContain("ScoringContext")
                .doesNotContain("FeatureSnapshotReader")
                .doesNotContain("FeatureSnapshotValue")
                .doesNotContain("FeatureSnapshotScalarType")
                .doesNotContain("FraudSignalEngine")
                .doesNotContain("FraudEngineDescriptor")
                .doesNotContain("FraudEngineResult")
                .doesNotContain("engineResults");
        assertThat(uiRuntime)
                .doesNotContain("ScoringContext")
                .doesNotContain("FeatureSnapshotReader")
                .doesNotContain("FeatureSnapshotValue")
                .doesNotContain("FeatureSnapshotScalarType")
                .doesNotContain("FraudSignalEngine")
                .doesNotContain("FraudEngineDescriptor")
                .doesNotContain("FraudEngineResult")
                .doesNotContain("TransactionRiskIntelligence")
                .doesNotContain("engineResults");
    }

    private String javaSources(Path root) throws IOException {
        return sourceFiles(root, ".java");
    }

    private String javaSourcesExcept(Path root, Path... excludedRoots) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            StringBuilder content = new StringBuilder();
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> Arrays.stream(excludedRoots).noneMatch(path::startsWith))
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
