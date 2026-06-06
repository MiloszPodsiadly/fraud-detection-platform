package com.frauddetection.alert.engineintelligence.dataset;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceFeedbackDatasetArchitectureGuardTest {

    @Test
    void datasetRecordDoesNotExposeRawSensitiveFields() {
        List<String> recordFields = Arrays.stream(EngineIntelligenceFeedbackDatasetRecord.class.getRecordComponents())
                .map(RecordComponent::getName)
                .map(this::compact)
                .toList();

        assertThat(recordFields).doesNotContain(
                "customerid",
                "accountid",
                "cardid",
                "deviceid",
                "merchantid",
                "pan",
                "iban",
                "email",
                "phone",
                "submittedby",
                "correlationid",
                "idempotencykeyhash",
                "requestpayloadhash",
                "rawpayload",
                "rawrequest",
                "rawresponse",
                "rawevidence",
                "rawcontribution",
                "featurevector",
                "stacktrace",
                "exceptionmessage",
                "token",
                "secret",
                "endpoint",
                "metadata",
                "groundtruth",
                "modeltraininglabel"
        );
    }

    @Test
    void fdp101DoesNotAddPublicApiUiDashboardOrGlobalSearch() throws Exception {
        assertThat(sources("docs/openapi/alert_service.openapi.yaml"))
                .doesNotContain("/api/v1/engine-intelligence/dataset")
                .doesNotContain("/api/v1/engine-intelligence/feedback/export")
                .doesNotContain("/api/v1/engine-intelligence/search");
        assertThat(filesContaining("analyst-console-ui/src", "FeedbackDataset")).isEmpty();
        assertThat(filesContaining("analyst-console-ui/src", "datasetExport")).isEmpty();
    }

    @Test
    void fdp101DoesNotWireDatasetExportIntoMlRuntimeOrDecisioning() throws Exception {
        assertThat(sources(
                "fraud-scoring-service/src/main/java",
                "ml-inference-service",
                "alert-service/src/main/java/com/frauddetection/alert/fraudcase",
                "alert-service/src/main/java/com/frauddetection/alert/regulated",
                "alert-service/src/main/java/com/frauddetection/alert/service/AlertManagementService.java"
        )).doesNotContain(
                "EngineIntelligenceFeedbackDataset",
                "engineintelligence.dataset"
        );
    }

    @Test
    void fdp101DatasetPackageDoesNotMutateDecisionsRulesPaymentsOrCases() throws Exception {
        String datasetSources = compact(sources(
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/dataset"
        ));

        assertThat(datasetSources).doesNotContain(
                "modelretraining",
                "modelpromotion",
                "ruleupdate",
                "paymentauthorization",
                "alertseverity",
                "fraudcasestatus",
                "setstatus",
                "setseverity",
                "dashboard",
                "globalsearch"
        );
    }

    @Test
    void docsStateBoundedInternalFoundationAndNoTrainingClaims() throws Exception {
        String docs = sources("docs/architecture/engine_intelligence_feedback_dataset_export.md");
        String index = sources("docs/architecture/index.md");

        assertThat(docs)
                .contains("FDP-101 adds an internal, bounded feedback dataset export foundation")
                .contains("does not add a public API endpoint")
                .contains("does not add a public API endpoint, analyst-console UI, dashboard, model retraining job")
                .contains("`feedbackLabel` is an evaluation label, not ground truth and not a model-training label")
                .contains("Missing ML engine output is represented with null ML risk and score bucket fields")
                .contains("Missing engine-intelligence")
                .contains("engineIntelligenceProjectionStatus=MISSING")
                .contains("the newest `submittedAt` feedback wins, with `feedbackId` as the stable")
                .contains("Projection read failure is not treated as an empty projection set");
        assertThat(index).contains("engine_intelligence_feedback_dataset_export.md");
    }

    private List<String> filesContaining(String relativeRoot, String needle) throws IOException {
        Path root = repositoryRoot();
        Path scanRoot = root.resolve(relativeRoot);
        if (!Files.exists(scanRoot)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(scanRoot)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(file -> contains(file, needle))
                    .map(root::relativize)
                    .map(Path::toString)
                    .toList();
        }
    }

    private boolean contains(Path file, String needle) {
        try {
            return Files.readString(file).contains(needle);
        } catch (IOException exception) {
            throw new IllegalStateException("FDP101_SOURCE_SCAN_FAILED", exception);
        }
    }

    private String sources(String... relativePaths) throws IOException {
        StringBuilder content = new StringBuilder();
        for (String relativePath : relativePaths) {
            Path path = repositoryRoot().resolve(relativePath);
            if (Files.isRegularFile(path)) {
                content.append(Files.readString(path)).append('\n');
            } else if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.walk(path)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        content.append(readTextIfPossible(file)).append('\n');
                    }
                }
            }
        }
        return content.toString();
    }

    private String readTextIfPossible(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException exception) {
            return "";
        }
    }

    private Path repositoryRoot() {
        Path current = Path.of(".").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("alert-service"))
                    && Files.isDirectory(candidate.resolve("common-events"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("REPOSITORY_ROOT_MISSING");
    }

    private String compact(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
