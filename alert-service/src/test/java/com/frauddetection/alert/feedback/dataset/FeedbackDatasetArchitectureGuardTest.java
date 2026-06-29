package com.frauddetection.alert.feedback.dataset;

import com.frauddetection.alert.feedback.FraudFeedbackRecord;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.CompoundIndexes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackDatasetArchitectureGuardTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path DATASET_MAIN = Path.of("src/main/java/com/frauddetection/alert/feedback/dataset");

    @Test
    void feedbackDatasetPackageDoesNotExposePublicApiOrRuntimeTrigger() throws IOException {
        String source = datasetProductionText();

        assertThat(source)
                .doesNotContain("@RestController", "@Controller", "@RequestMapping")
                .doesNotContain("@Scheduled", "CommandLineRunner", "ApplicationRunner", "@ShellComponent")
                .doesNotContain("KafkaTemplate");
    }

    @Test
    void feedbackDatasetPackageDoesNotAddMlEvaluationTrainingPromotionOrPaymentBehavior() throws IOException {
        String source = datasetProductionText();

        assertThat(source)
                .doesNotContain(
                        "precision",
                        "recall",
                        "calibration",
                        "trainModel",
                        "retrainModel",
                        "promoteModel",
                        "thresholdRecommendation",
                        "authorizePayment",
                        "approvePayment",
                        "declinePayment",
                        "blockTransaction",
                        "FraudCase"
                );
    }

    @Test
    void feedbackDatasetDoesNotUseEngineIntelligenceDatasetAsSourceOfTruth() throws IOException {
        String source = datasetProductionText();

        assertThat(source)
                .doesNotContain("com.frauddetection.alert.engineintelligence.dataset")
                .doesNotContain("EngineIntelligenceFeedbackDataset")
                .doesNotContain("engine_intelligence_feedback")
                .doesNotContain("feedback_dataset.py");
    }

    @Test
    void openApiDoesNotExposeFeedbackDatasetPath() throws IOException {
        String openApi = Files.readString(ROOT.resolve("docs/openapi/alert_service.openapi.yaml"));

        assertThat(openApi).doesNotContain("feedback-dataset", "fraud-feedback-dataset");
    }

    @Test
    void builderDoesNotUseUnboundedFindAll() throws IOException {
        assertThat(datasetProductionText()).doesNotContain(".findAll(", "findAll()");
    }

    @Test
    void fraudFeedbackRecordHasDatasetQueryIndex() {
        CompoundIndexes indexes = FraudFeedbackRecord.class.getAnnotation(CompoundIndexes.class);

        assertThat(indexes).isNotNull();
        assertThat(Arrays.stream(indexes.value()).map(org.springframework.data.mongodb.core.index.CompoundIndex::name))
                .contains("fraud_feedback_dataset_created_label_id_idx");
    }

    @Test
    void documentationMentionsFdp123Boundary() throws IOException {
        String docs = Files.readString(ROOT.resolve("docs/architecture/feedback_dataset_governance.md"))
                + "\n"
                + Files.readString(ROOT.resolve("docs/architecture/feedback_dataset_builder.md"));

        assertThat(docs)
                .contains("FDP-123")
                .contains("FeedbackDatasetEligibilityPolicy")
                .contains("not training")
                .contains("separate bounded context")
                .contains("DATASET_METADATA")
                .contains("DATASET_RECORD")
                .contains("Consumers must ignore or separately parse lines where `type != DATASET_RECORD`")
                .contains("`rawRowsRead` is the bounded number of candidate rows fetched")
                .contains("pseudonymous references are not anonymization")
                .contains("identifier strategy must be reviewed separately");
    }

    @Test
    void feedbackDatasetSchemaArtifactExists() {
        assertThat(ROOT.resolve("docs/schemas/feedback_dataset_record.schema.json")).exists();
    }

    private String datasetProductionText() throws IOException {
        Path root = Path.of("").toAbsolutePath().endsWith("alert-service")
                ? DATASET_MAIN
                : ROOT.resolve("alert-service").resolve(DATASET_MAIN);
        StringBuilder source = new StringBuilder();
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .forEach(path -> source.append(read(path)).append('\n'));
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
        if (current.endsWith("alert-service")) {
            return current.getParent();
        }
        return current;
    }
}
