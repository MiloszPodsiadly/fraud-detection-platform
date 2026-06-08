package com.frauddetection.alert.governance.shadowperformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.alert.audit.read.ReadAccessAuditClassifier;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class ShadowPerformanceReadApiArchitectureGuardTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path PRODUCTION_ROOT = Path.of("src/main/java/com/frauddetection/alert");
    private static final Path DOC = ROOT.resolve("docs/architecture/shadow_performance_read_api.md");
    private static final Path OPENAPI = ROOT.resolve("docs/openapi/alert_service.openapi.yaml");

    @Test
    void serializedResponseDoesNotExposeRawDataOrOverclaimFields() throws Exception {
        String payload = new ObjectMapper().writeValueAsString(
                ShadowPerformanceSummaryResponse.from(new StaticShadowPerformanceSummaryProvider().currentSummary().orElseThrow())
        );

        for (String term : new String[]{
                "rawModelCard",
                "rawEvaluationReport",
                "rawDataset",
                "rawFdp102Jsonl",
                "evaluationRecordId",
                "transactionReference",
                "eval-",
                "txnref-",
                "customerId",
                "accountId",
                "cardId",
                "deviceId",
                "merchantId",
                "analystId",
                "submittedBy",
                "correlationId",
                "requestHash",
                "idempotencyKey",
                "rawPayload",
                "rawFeatureVector",
                "rawMlRequest",
                "rawMlResponse",
                "endpoint",
                "token",
                "secret",
                "stacktrace",
                "exceptionMessage",
                "groundTruth",
                "trainingLabel",
                "modelTrainingLabel",
                "finalDecision",
                "paymentAuthorization",
                "productionApproved",
                "promotionApproved",
                "promotionReady",
                "thresholdRecommendation",
                "recommendedThreshold",
                "championCandidate",
                "deployRecommendation",
                "analystRecommendation"
        }) {
            assertThat(payload).doesNotContain(term);
        }
    }

    @Test
    void readAccessClassifierRecognizesShadowPerformanceSummary() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/governance/shadow-performance/summary/current");
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/v1/governance/shadow-performance/summary/current"
        );

        var target = new ReadAccessAuditClassifier().classify(request).orElseThrow();

        assertThat(target.endpointCategory()).isEqualTo(ReadAccessEndpointCategory.SHADOW_PERFORMANCE_SUMMARY);
        assertThat(target.resourceType()).isEqualTo(ReadAccessResourceType.SHADOW_PERFORMANCE_SUMMARY);
        assertThat(target.resourceId()).isNull();
    }

    @Test
    void readApiDoesNotReadRawSourcesOrRecomputeMetrics() throws Exception {
        String source = shadowPerformanceSource();

        assertThat(source).doesNotContain(
                "read_fdp102_jsonl",
                "Fdp102Jsonl",
                "EvaluationRunner",
                "build_model_card",
                "ModelCardGenerator",
                "precisionAtBudget =",
                "recallAtTopK =",
                "falsePositiveRate =",
                "recompute",
                "disagreementSummary.values().stream()"
        );
    }

    @Test
    void readDoesNotAddRuntimeSideEffects() throws Exception {
        String source = shadowPerformanceSource();

        assertThat(source).doesNotContain(
                "KafkaTemplate",
                "MongoTemplate",
                "RestTemplate",
                "RestClient",
                "WebClient",
                "FraudInferenceHandler",
                "write_threshold",
                "threshold_config",
                "promote_model",
                "model_registry_write",
                "write_model_artifact",
                "train_model",
                "retraining",
                "approve_transaction",
                "decline_transaction",
                "block_transaction",
                "alertSeverity",
                "fraudCaseStatus"
        );
    }

    @Test
    void openApiDocumentsShadowPerformanceEndpointAndBoundaries() throws Exception {
        String openApi = Files.readString(OPENAPI);

        assertThat(openApi).contains(
                "/api/v1/governance/shadow-performance/summary/current",
                "shadow-performance:read",
                "diagnostic-only",
                "not model promotion approval",
                "not threshold recommendation",
                "not production decisioning approval",
                "not payment authorization",
                "not automatic approve/decline/block logic",
                "not analyst recommendation logic",
                "ShadowPerformanceSummaryResponse",
                "evaluationPopulation"
        );
        assertThat(openApi).doesNotContain(
                "/api/v1/governance/shadow-performance/promotion",
                "/api/v1/governance/shadow-performance/threshold",
                "/api/v1/governance/shadow-performance/decisioning"
        );
    }

    @Test
    void docsStateReadOnlyAuthorizedDiagnosticBoundary() throws Exception {
        String doc = Files.readString(DOC);

        assertThat(doc).contains(
                "read-only API boundary",
                "requires the explicit `shadow-performance:read` authority",
                "exposes only validated FDP-105 Shadow Performance Summary fields",
                "does not recompute metrics",
                "does not read FDP-102 JSONL exports",
                "does not expose raw Model Cards",
                "not a dashboard",
                "not model promotion approval",
                "not threshold recommendation",
                "not production decisioning approval",
                "not payment authorization",
                "not automatic approve/decline/block logic",
                "not analyst recommendation logic"
        );
    }

    private String shadowPerformanceSource() throws Exception {
        Path packageRoot = PRODUCTION_ROOT.resolve("governance/shadowperformance");
        return Files.walk(packageRoot)
                .filter(path -> path.toString().endsWith(".java"))
                .map(this::readUnchecked)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read " + path, exception);
        }
    }
}
