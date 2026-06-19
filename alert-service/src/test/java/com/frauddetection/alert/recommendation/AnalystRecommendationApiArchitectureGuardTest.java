package com.frauddetection.alert.recommendation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AnalystRecommendationApiArchitectureGuardTest {

    @Test
    void alertServiceDoesNotComputeAnalystRecommendation() throws Exception {
        String alertSources = sources(repositoryRoot().resolve("alert-service/src/main/java"));

        assertThat(alertSources).doesNotContain(
                "class AnalystRecommendationService",
                "calculateAnalystRecommendation",
                "computeAnalystRecommendation",
                "deriveAnalystRecommendation",
                "RECOMMEND_CASE_CREATION,",
                "RECOMMEND_REVIEW,"
        );
    }

    @Test
    void fraudScoringServiceOwnsAnalystRecommendationGeneration() throws Exception {
        String scoringSources = sources(repositoryRoot().resolve("fraud-scoring-service/src/main/java"));

        assertThat(scoringSources)
                .contains("class AnalystRecommendationService")
                .contains("AnalystRecommendationResult")
                .contains("RECOMMEND_REVIEW")
                .contains("RECOMMEND_CASE_CREATION");
    }

    @Test
    void noFrontendAnalystRecommendationUiWasAddedInFdp119() throws Exception {
        String frontendSources = sources(repositoryRoot().resolve("analyst-console-ui/src"));

        assertThat(frontendSources).doesNotContain(
                "RECOMMEND_REVIEW",
                "RECOMMEND_CASE_CREATION"
        );
    }

    @Test
    void recommendationLayerDoesNotDeclarePaymentOrAutomaticDecisioning() throws Exception {
        String sources = sources(repositoryRoot().resolve("common-events/src/main/java/com/frauddetection/common/events/recommendation"))
                + sources(repositoryRoot().resolve("fraud-scoring-service/src/main/java/com/frauddetection/scoring/service"));

        assertThat(sources)
                .contains(
                        "notPaymentAuthorization",
                        "notAutomaticDecisioning",
                        "notCaseAction",
                        "notWorkflowAction",
                        "notModelPromotion",
                        "notThresholdRecommendation"
                )
                .doesNotContain(
                        "approvePayment(",
                        "declinePayment(",
                        "blockPayment(",
                        "finalDecision(",
                        "paymentDecision(",
                        "automaticApprove(",
                        "automaticDecline(",
                        "automaticBlock("
        );
    }

    private String sources(Path root) throws Exception {
        if (!Files.exists(root)) {
            return "";
        }
        StringBuilder content = new StringBuilder();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".java")
                            || file.toString().endsWith(".js")
                            || file.toString().endsWith(".jsx"))
                    .toList()) {
                content.append(Files.readString(path)).append('\n');
            }
        }
        return content.toString();
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
