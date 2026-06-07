package com.frauddetection.alert.engineintelligence.dataset;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceFeedbackDatasetScopeGuardTest {

    private static final Path ROOT = repositoryRoot();

    @Test
    void doesNotAddPublicApiEndpoint() throws IOException {
        assertThat(datasetProductionText()).doesNotContain("@RestController", "@Controller", "@RequestMapping");
    }

    @Test
    void doesNotUpdateOpenApi() throws IOException {
        assertThat(Files.readString(ROOT.resolve("docs/openapi/alert_service.openapi.yaml")))
                .doesNotContain("feedback-dataset", "dataset-export");
    }

    @Test
    void doesNotAddUi() throws IOException {
        assertThat(allText(ROOT.resolve("analyst-console-ui/src"))).doesNotContain("FeedbackDataset", "dataset export");
    }

    @Test
    void doesNotAddScheduledExportJob() throws IOException {
        assertThat(datasetProductionText()).doesNotContain("@Scheduled", "SchedulingConfigurer");
    }

    @Test
    void doesNotAddCliOrJobExportTrigger() throws IOException {
        assertThat(datasetProductionText())
                .doesNotContain("CommandLineRunner", "ApplicationRunner", "JobLauncher", "@ShellComponent");
    }

    @Test
    void doesNotAddPythonEvaluationRunner() throws IOException {
        assertThat(allText(ROOT.resolve("ml-inference-service"))).doesNotContain("feedback_dataset_export", "FDP-102");
    }

    @Test
    void doesNotAddModelRetraining() throws IOException {
        assertThat(datasetProductionText()).doesNotContain("retrain", "Retraining");
    }

    @Test
    void doesNotAddModelPromotion() throws IOException {
        assertThat(datasetProductionText()).doesNotContain("promote", "Promotion");
    }

    @Test
    void doesNotChangeTransactionScoredEvent() throws IOException {
        assertThat(Files.readString(ROOT.resolve("common-events/src/main/java/com/frauddetection/common/events/contract/TransactionScoredEvent.java")))
                .contains("EngineIntelligenceSummary engineIntelligence")
                .doesNotContain("engineResults");
    }

    @Test
    void doesNotChangeExistingEngineIntelligenceShape() throws IOException {
        assertThat(datasetProductionText()).doesNotContain("EngineIntelligenceSummary(", "TransactionScoredEvent(");
    }

    @Test
    void doesNotChangeScoringBehavior() throws IOException {
        assertThat(allText(ROOT.resolve("fraud-scoring-service/src/main/java"))).doesNotContain("EngineIntelligenceFeedbackDataset");
    }

    @Test
    void doesNotMutateAlertSeverity() throws IOException {
        assertThat(datasetProductionText()).doesNotContain("setRiskLevel", "setFraudScore");
    }

    @Test
    void doesNotMutateFraudCaseStatus() throws IOException {
        assertThat(datasetProductionText()).doesNotContain("FraudCaseStatus", "setStatus");
    }

    @Test
    void doesNotCallPaymentAuthorization() throws IOException {
        assertThat(datasetProductionText())
                .doesNotContain("PaymentAuthorizationService", "authorizePayment", "paymentAuthorization(");
    }

    @Test
    void doesNotAddRecommendationService() throws IOException {
        assertThat(datasetProductionText()).doesNotContain("Recommendation");
    }

    private String datasetProductionText() throws IOException {
        return allText(ROOT.resolve("alert-service/src/main/java/com/frauddetection/alert/engineintelligence/dataset"));
    }

    private String allText(Path root) throws IOException {
        if (!Files.exists(root)) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(this::isTextSource).toList()) {
                text.append(Files.readString(path)).append('\n');
            }
        }
        return text.toString();
    }

    private boolean isTextSource(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".java")
                || fileName.endsWith(".js")
                || fileName.endsWith(".jsx")
                || fileName.endsWith(".ts")
                || fileName.endsWith(".tsx")
                || fileName.endsWith(".py")
                || fileName.endsWith(".md")
                || fileName.endsWith(".yaml")
                || fileName.endsWith(".yml")
                || fileName.endsWith(".txt");
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        if (current.endsWith("alert-service")) {
            return current.getParent();
        }
        return current;
    }
}
