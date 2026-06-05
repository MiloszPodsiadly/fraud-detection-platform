package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
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

class EngineIntelligenceApiArchitectureGuardTest {

    private static final List<String> FDP97_ANALYST_CONSOLE_ENGINE_INTELLIGENCE_ALLOWED_FILES = List.of(
            "analyst-console-ui/src/api/alertsApi.js",
            "analyst-console-ui/src/api/alertsApi.test.js",
            "analyst-console-ui/src/components/EngineIntelligenceAnalystUiDisplayDocsTest.test.js",
            "analyst-console-ui/src/components/EngineIntelligenceFeedbackPanel.jsx",
            "analyst-console-ui/src/components/EngineIntelligenceFeedbackPanel.test.jsx",
            "analyst-console-ui/src/components/EngineIntelligencePanel.jsx",
            "analyst-console-ui/src/components/EngineIntelligencePanel.test.jsx",
            "analyst-console-ui/src/components/EngineIntelligencePanelScopeGuard.test.js",
            "analyst-console-ui/src/pages/FraudCaseDetailsPage.jsx",
            "analyst-console-ui/src/pages/FraudCaseDetailsPage.test.jsx",
            "analyst-console-ui/src/styles.css"
    );

    @Test
    void onlyBoundedReadModelPackageMayExposeEngineIntelligence() throws Exception {
        String legacyApiSources = sources(
                "alert-service/src/main/java/com/frauddetection/alert/api",
                "alert-service/src/main/java/com/frauddetection/alert/controller"
        );
        String openApi = sources("docs/openapi/alert_service.openapi.yaml");

        assertThat(legacyApiSources).doesNotContain("EngineIntelligence");
        assertThat(openApi)
                .contains("EngineIntelligenceReadModel")
                .doesNotContain("EngineIntelligenceProjection");
    }

    @Test
    void projectionClassesAreNotUsedAsApiResponses() throws Exception {
        assertThat(EngineIntelligenceReadController.class.getDeclaredMethod("read", String.class).getReturnType())
                .isEqualTo(EngineIntelligenceReadModel.class);
        assertThat(Arrays.stream(EngineIntelligenceReadModel.class.getRecordComponents())
                .map(RecordComponent::getType)
                .toList())
                .doesNotContain(EngineIntelligenceProjection.class);
    }

    @Test
    void uiExposesEngineIntelligenceOnlyThroughBoundedReadAndFeedbackSurfaces() throws Exception {
        assertThat(filesContainingIgnoringCase("analyst-console-ui/src", "engineIntelligence"))
                .isSubsetOf(FDP97_ANALYST_CONSOLE_ENGINE_INTELLIGENCE_ALLOWED_FILES);
    }

    @Test
    void feedbackWorkflowDoesNotImportScoringMlRulesOrPaymentAuthorization() throws Exception {
        String feedbackSources = sources(
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/feedback",
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/api/EngineIntelligenceFeedbackController.java",
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/api/EngineIntelligenceFeedbackReadController.java",
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/api/EngineIntelligenceFeedbackService.java",
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/api/EngineIntelligenceFeedbackReadService.java",
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/api/EngineIntelligenceFeedbackRequest.java",
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/api/EngineIntelligenceFeedbackResponse.java",
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/api/EngineIntelligenceFeedbackReadModel.java",
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/api/EngineIntelligenceFeedbackEntryReadModel.java",
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/api/EngineIntelligenceFeedbackPage.java"
        ).toLowerCase(java.util.Locale.ROOT);

        assertThat(feedbackSources).doesNotContain(
                "import com.frauddetection.alert.scoring",
                "import com.frauddetection.alert.ml",
                "import com.frauddetection.alert.rules",
                "orchestratorclient",
                "paymentauthorizationclient",
                "transactionscoredeventpublisher",
                "modeltrainingservice",
                "trainingdatasetexport",
                "transactionaloutbox"
        );
    }

    @Test
    void feedbackModelDoesNotContainDecisioningFields() throws Exception {
        String feedbackSources = sources(
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/feedback",
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/api/EngineIntelligenceFeedbackRequest.java",
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/api/EngineIntelligenceFeedbackResponse.java",
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/api/EngineIntelligenceFeedbackReadModel.java",
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/api/EngineIntelligenceFeedbackEntryReadModel.java",
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/api/EngineIntelligenceFeedbackPage.java"
        );

        assertThat(feedbackSources).doesNotContain(
                "finalDecision",
                "recommendedAction",
                "alertSeverity",
                "fraudCaseStatus",
                "paymentAuthorization",
                "modelTrainingLabel",
                "groundTruth",
                "ruleUpdateRequest"
        );
    }

    @Test
    void decisioningStillDoesNotImportEngineIntelligence() throws Exception {
        String decisioningSources = sources(
                "alert-service/src/main/java/com/frauddetection/alert/fraudcase",
                "alert-service/src/main/java/com/frauddetection/alert/regulated",
                "alert-service/src/main/java/com/frauddetection/alert/suspicious",
                "alert-service/src/main/java/com/frauddetection/alert/service/AlertManagementService.java"
        );

        assertThat(decisioningSources).doesNotContain(
                "EngineIntelligenceProjection",
                "EngineIntelligenceProjectionRepository",
                "EngineIntelligenceReadService",
                "EngineIntelligenceReadModel"
        );
    }

    @Test
    void readServiceDoesNotCallScoringMlRulesOrOrchestratorOrKafka() throws Exception {
        String readService = sources(
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/api/EngineIntelligenceReadService.java",
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/api/EngineIntelligenceFeedbackReadService.java"
        ).toLowerCase(java.util.Locale.ROOT);

        assertThat(readService).doesNotContain(
                "scoring",
                "ml",
                "rules",
                "orchestrator",
                "kafka",
                "transactionscoredevent",
                "outbox",
                "paymentauthorization",
                "training"
        );
    }

    @Test
    void feedbackReadResponseAndOpenApiDoNotExposeRawInternalFields() throws Exception {
        String readDtoSources = sources(
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/api/EngineIntelligenceFeedbackReadModel.java",
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/api/EngineIntelligenceFeedbackEntryReadModel.java",
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/api/EngineIntelligenceFeedbackPage.java"
        );
        String openApi = sources("docs/openapi/alert_service.openapi.yaml");
        String feedbackReadSchemas = openApi.substring(
                openApi.indexOf("EngineIntelligenceFeedbackReadModel:"),
                openApi.indexOf("EngineIntelligenceReadModel:")
        );
        String combined = readDtoSources + feedbackReadSchemas;

        assertThat(combined).contains("EngineIntelligenceFeedbackReadModel");
        assertThat(combined).doesNotContain(
                "EngineIntelligenceFeedbackDocument",
                "submittedBy:",
                "idempotencyKeyHash",
                "requestPayloadHash",
                "correlationId:",
                "createdAt:",
                "modelTrainingLabel:",
                "groundTruth",
                "ruleUpdateRequest:"
        );
    }

    @Test
    void openApiExposesOnlyTransactionScopedEngineIntelligenceReadAndFeedbackEndpoints() throws Exception {
        String openApi = sources("docs/openapi/alert_service.openapi.yaml");

        assertThat(openApi.split("/engine-intelligence", -1)).hasSize(3);
        assertThat(openApi).contains("/api/v1/transactions/scored/{transactionId}/engine-intelligence:");
        assertThat(openApi).contains("/api/v1/transactions/scored/{transactionId}/engine-intelligence/feedback:");
        assertThat(openApi)
                .contains("EngineIntelligenceFeedbackReadModel")
                .contains("EngineIntelligenceFeedbackEntryReadModel")
                .contains("EngineIntelligenceFeedbackPage")
                .contains("FDP-99 returns the first bounded page of latest feedback.")
                .contains("Cursor-based continuation is future scope.")
                .contains("page.hasMore indicates additional feedback exists, not that FDP-99 provides navigation.")
                .contains("default: 25")
                .contains("maximum: 50")
                .contains("engine-intelligence:feedback:read");
        assertThat(openApi).doesNotContain(
                "/api/v1/engine-intelligence:",
                "/api/v1/engine-intelligence/search:",
                "/api/v1/fraud-cases/{caseId}/engine-intelligence:"
        );
    }

    @Test
    void feedbackReadArchitectureDocsStateReviewOnlyBoundaries() throws Exception {
        String docs = sources("docs/architecture/engine_intelligence_feedback_read_model.md");

        assertThat(docs)
                .contains("Feedback can be reviewed, not executed.")
                .contains("FDP-99 exposes captured feedback through a bounded, authorized, transaction-scoped read model.")
                .contains("FDP-99 is governance/review only.")
                .contains("FDP-99 does not add analytics dashboards, global search, case aggregation, training export, model retraining, rule updates, approve/decline/block, alert severity changes, or fraud case status changes.")
                .contains("submittedBy is omitted by default in FDP-99 v1.")
                .contains("Any future submittedBy exposure requires stronger explicit permission and separate review.")
                .contains("Feedback is analyst perception/review input, not ground truth, training label, model correction, scoring override, or final decision.")
                .contains("FDP-99 returns the first bounded page of latest feedback.")
                .contains("Cursor-based continuation is future scope.")
                .contains("hasMore indicates additional feedback exists, not navigation state.")
                .contains("No unbounded findAll/read-all endpoint is allowed.");
    }

    @Test
    void governanceIndexesDocumentFeedbackReadEndpointAndBoundary() throws Exception {
        String architectureIndex = sources("docs/architecture/index.md");
        String apiSurface = sources("docs/api/api_surface_v1.md");

        assertThat(architectureIndex)
                .contains("engine_intelligence_feedback_read_model.md")
                .contains("Engine intelligence feedback read model")
                .contains("FDP-99");
        assertThat(apiSurface)
                .contains("GET /api/v1/transactions/scored/{transactionId}/engine-intelligence/feedback")
                .contains("ENGINE_INTELLIGENCE_FEEDBACK_READ")
                .contains("Bounded first page")
                .contains("default 25")
                .contains("max 50")
                .contains("no submittedBy")
                .contains("No execution, decisioning, retraining, or rule updates");
    }

    private String sources(String... relativePaths) throws IOException {
        StringBuilder sources = new StringBuilder();
        for (String relativePath : relativePaths) {
            Path path = repositoryRoot().resolve(relativePath);
            if (Files.isRegularFile(path)) {
                sources.append(Files.readString(path));
            } else if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.walk(path)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        sources.append(Files.readString(file));
                    }
                }
            }
        }
        return sources.toString();
    }

    private List<String> filesContainingIgnoringCase(String relativeRoot, String needle) throws IOException {
        Path repositoryRoot = repositoryRoot();
        Path scanRoot = repositoryRoot.resolve(relativeRoot);
        if (!Files.exists(scanRoot)) {
            return List.of();
        }
        String normalizedNeedle = needle.toLowerCase(Locale.ROOT);
        try (Stream<Path> files = Files.walk(scanRoot)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(file -> containsIgnoringCase(file, normalizedNeedle))
                    .map(repositoryRoot::relativize)
                    .map(EngineIntelligenceApiArchitectureGuardTest::normalize)
                    .sorted()
                    .toList();
        }
    }

    private static boolean containsIgnoringCase(Path path, String normalizedNeedle) {
        try {
            return Files.readString(path).toLowerCase(Locale.ROOT).contains(normalizedNeedle);
        } catch (IOException exception) {
            throw new IllegalStateException("ENGINE_INTELLIGENCE_UI_SOURCE_SCAN_FAILED", exception);
        }
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
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
}
