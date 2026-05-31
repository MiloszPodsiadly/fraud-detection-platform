package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FraudEngineAggregationRuntimeIsolationTest {

    @Test
    void transactionScoredEventDoesNotExposeAggregation() {
        assertThat(Arrays.stream(TransactionScoredEvent.class.getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain("engineResults", "aggregationResult", "agreementStatus", "strongestSignals");
    }

    @Test
    void externalProductionSourcesDoNotReferenceAggregationPackage() throws Exception {
        Path root = repositoryRoot();
        assertThat(javaSources(root.resolve("common-events/src/main/java")))
                .doesNotContain("FraudEngineAggregationResult", "FraudEngineAgreementStatus", "FraudEngineStrongestSignal");
        assertThat(javaSources(root.resolve("alert-service/src/main/java"))).doesNotContain("orchestration.aggregation");
        assertThat(sourceFiles(root.resolve("analyst-console-ui/src"))).doesNotContain("orchestration.aggregation");
        assertThat(javaSources(root.resolve("fraud-scoring-service/src/main/java/com/frauddetection/scoring/controller")))
                .doesNotContain("orchestration.aggregation");
        assertThat(javaSources(root.resolve("fraud-scoring-service/src/main/java/com/frauddetection/scoring/messaging")))
                .doesNotContain("FraudEngineAggregationResult", "orchestration.aggregation");
        assertThat(Files.readString(root.resolve(
                "fraud-scoring-service/src/main/java/com/frauddetection/scoring/service/CompositeFraudScoringEngine.java"
        ))).doesNotContain("FraudEngineAggregationResult", "orchestration.aggregation");
    }

    @Test
    void aggregationPackageContainsNoDecisioningSurface() throws Exception {
        String aggregation = javaSources(repositoryRoot().resolve(
                "fraud-scoring-service/src/main/java/com/frauddetection/scoring/orchestration/aggregation"
        )).toLowerCase();

        assertThat(aggregation).doesNotContain(
                "finaldecision",
                "recommendedaction",
                "authorizationdecision",
                "paymentdecision",
                "platformriskscore",
                "platformrisklevel",
                "winningengine",
                "finaldecisionsource",
                "@component",
                "@service",
                "@controller"
        );
    }

    private String javaSources(Path root) throws Exception {
        return sourceFiles(root, ".java");
    }

    private String sourceFiles(Path root, String... suffixes) throws Exception {
        if (!Files.exists(root)) {
            return "";
        }
        try (Stream<Path> files = Files.walk(root)) {
            StringBuilder source = new StringBuilder();
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> suffixes.length == 0
                            || Arrays.stream(suffixes).anyMatch(path.toString()::endsWith))
                    .toList()) {
                source.append(Files.readString(file)).append('\n');
            }
            return source.toString();
        }
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
