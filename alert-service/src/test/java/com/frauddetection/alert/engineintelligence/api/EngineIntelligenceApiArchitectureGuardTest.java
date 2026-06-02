package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceApiArchitectureGuardTest {

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
    void uiStillDoesNotReferenceEngineIntelligence() throws Exception {
        assertThat(sources("analyst-console-ui/src")).doesNotContainIgnoringCase("engineIntelligence");
    }

    @Test
    void feedbackStillDoesNotReferenceEngineIntelligence() throws Exception {
        assertThat(sources("alert-service/src/main/java/com/frauddetection/alert/feedback"))
                .doesNotContain("EngineIntelligence");
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
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/api/EngineIntelligenceReadService.java"
        ).toLowerCase(java.util.Locale.ROOT);

        assertThat(readService).doesNotContain(
                "scoring",
                "ml",
                "rules",
                "orchestrator",
                "kafka",
                "transactionscoredevent"
        );
    }

    @Test
    void openApiStillExposesOnlyOneTransactionScopedEngineIntelligenceEndpoint() throws Exception {
        String openApi = sources("docs/openapi/alert_service.openapi.yaml");

        assertThat(openApi.split("/engine-intelligence", -1)).hasSize(2);
        assertThat(openApi).contains("/api/v1/transactions/scored/{transactionId}/engine-intelligence:");
        assertThat(openApi).doesNotContain(
                "/api/v1/engine-intelligence:",
                "/api/v1/engine-intelligence/search:",
                "/api/v1/fraud-cases/{caseId}/engine-intelligence:"
        );
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
