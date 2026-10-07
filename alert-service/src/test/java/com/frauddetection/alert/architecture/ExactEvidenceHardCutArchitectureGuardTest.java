package com.frauddetection.alert.architecture;

import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.frauddetection.alert.engineintelligence.CanonicalTransactionScoredEventDeserializationTest;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExactEvidenceHardCutArchitectureGuardTest {

    private static final Path ROOT = repositoryRoot();
    private final CanonicalTransactionScoredEventDeserializationTest canonicalEventContract =
            new CanonicalTransactionScoredEventDeserializationTest();

    @Test
    void everyCurrentConsumerPreservesBothCanonicalEvidenceOutcomes() throws Exception {
        canonicalEventContract.everyCurrentConsumerPreservesCanonicalExactEvidence();
        canonicalEventContract.everyCurrentConsumerPreservesCanonicalExplicitOmission();
    }

    @Test
    void everyCurrentConsumerRejectsMalformedCanonicalEvidence() throws Exception {
        canonicalEventContract.everyCurrentConsumerRejectsMalformedCanonicalEvidence();
        canonicalEventContract.malformedJsonFailsEveryCurrentConsumerBoundary();
    }

    @Test
    void currentProjectionConstructionRequiresOccurrenceOwnership() {
        assertThat(Arrays.asList(EngineIntelligenceProjection.class.getConstructors()))
                .singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterTypes())
                        .contains(ScoringOccurrenceOwnership.class));

        assertThat(Arrays.stream(EngineIntelligenceProjectionMapper.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getName().equals("map"))
                .toList())
                .isNotEmpty()
                .allSatisfy(method -> assertThat(method.getParameterTypes())
                        .contains(ScoringOccurrenceOwnership.class));
    }

    @Test
    void productionSourceDoesNotRestoreRemovedOccurrenceOrEvidenceFallbacks() throws IOException {
        String productionSource = sourceText(List.of(
                ROOT.resolve("common-events/src/main"),
                ROOT.resolve("fraud-scoring-service/src/main"),
                ROOT.resolve("alert-service/src/main"),
                ROOT.resolve("ml-inference-service/app"),
                ROOT.resolve("ml-inference-service/offline_evaluation")
        ));

        assertThat(productionSource).doesNotContain(
                "UNKNOWN_OCCURRENCE",
                "ScoringOccurrenceOwnership.unknown",
                "historicalUnknownQuery",
                "replaceHistoricalUnknown",
                "HISTORICAL_OCCURRENCE_CLAIMED",
                "historicalWithoutFence",
                "LEGITIMATE_ABSENCE",
                "CURRENT_ML_PREDICTION_EVIDENCE_LEGITIMATE_ABSENCE_UNSUPPORTED"
        );
    }

    @Test
    void cutoverProcedureForbidsRuntimeCompatibilityRollback() throws IOException {
        String procedure = Files.readString(
                ROOT.resolve("docs/architecture/scoring_occurrence_ownership_migration.md")
        );

        assertThat(procedure)
                .contains("Rollback must never restore identity-free runtime interpretation")
                .contains("current ML inference")
                .contains("source count = migrated count + archived count + quarantined count");
    }

    private String sourceText(List<Path> roots) throws IOException {
        StringBuilder source = new StringBuilder();
        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            try (var paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java") || path.toString().endsWith(".py"))
                        .sorted()
                        .forEach(path -> source.append(read(path)).append('\n'));
            }
        }
        return source.toString();
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        return current.endsWith("alert-service") ? current.getParent() : current;
    }
}
