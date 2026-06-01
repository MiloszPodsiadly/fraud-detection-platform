package com.frauddetection.common.events.intelligence;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceInternalModelIsolationTest {
    private static final List<String> INTERNAL_TYPES = List.of(
            "FraudEngineAggregationResult",
            "NormalizedFraudEngineResult",
            "FraudEngineStrongestSignal",
            "BoundedFraudEngineEvidenceSummary",
            "BoundedFraudEngineContributionSummary"
    );

    @Test
    void commonEventsProductionCodeDoesNotImportAggregationPackage() throws Exception {
        assertThat(javaSources(repositoryRoot().resolve("common-events/src/main/java")))
                .doesNotContain("com.frauddetection.scoring.orchestration.aggregation");
    }

    @Test
    void transactionScoredEventDoesNotContainInternalAggregationTypes() {
        assertThat(Arrays.stream(TransactionScoredEvent.class.getRecordComponents())
                .map(component -> component.getGenericType().getTypeName()))
                .noneMatch(type -> INTERNAL_TYPES.stream().anyMatch(type::contains));
    }

    @Test
    void publicDtoClassesDoNotContainInternalAggregationFields() {
        assertThat(EngineIntelligencePublicTypes.records().stream()
                .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .map(component -> component.getGenericType().getTypeName()))
                .noneMatch(type -> INTERNAL_TYPES.stream().anyMatch(type::contains));
    }

    @Test
    void publicDtoJsonDoesNotContainInternalClassNames() throws Exception {
        String json = EngineIntelligenceTestSupport.objectMapper().writeValueAsString(EngineIntelligenceTestSupport.summary());

        assertThat(json).doesNotContain(INTERNAL_TYPES.toArray(String[]::new));
    }

    @Test
    void commonEventsPomDoesNotDependOnFraudScoringService() throws Exception {
        assertThat(Files.readString(repositoryRoot().resolve("common-events/pom.xml")))
                .doesNotContain("<artifactId>fraud-scoring-service</artifactId>");
    }

    private String javaSources(Path root) throws Exception {
        try (Stream<Path> files = Files.walk(root)) {
            StringBuilder content = new StringBuilder();
            for (Path file : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                content.append(Files.readString(file)).append('\n');
            }
            return content.toString();
        }
    }

    private Path repositoryRoot() {
        Path current = Path.of(".").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve("common-events")) && Files.exists(candidate.resolve("fraud-scoring-service"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not resolve repository root from " + current);
    }
}
