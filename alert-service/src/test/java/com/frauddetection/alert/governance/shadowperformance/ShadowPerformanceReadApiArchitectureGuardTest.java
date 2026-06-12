package com.frauddetection.alert.governance.shadowperformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.frauddetection.alert.audit.read.ReadAccessAuditClassifier;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ShadowPerformanceReadApiArchitectureGuardTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path PRODUCTION_ROOT = Path.of("src/main/java/com/frauddetection/alert");
    private static final Path DOC = ROOT.resolve("docs/architecture/shadow_performance_read_api.md");
    private static final Path OPENAPI = ROOT.resolve("docs/openapi/alert_service.openapi.yaml");
    private static final Path UI_ROOT = ROOT.resolve("analyst-console-ui/src");
    private static final Path DOCKER_COMPOSE = ROOT.resolve("deployment/docker-compose.yml");
    private static final Path DEMO_COMPOSE = ROOT.resolve("deployment/docker-compose.shadow-performance-demo.yml");
    private static final Path MAKEFILE = ROOT.resolve("Makefile");
    private static final Path APP_PS1 = ROOT.resolve("scripts/app.ps1");

    @Test
    void serializedResponseDoesNotExposeRawDataOrOverclaimFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String payload = mapper.writeValueAsString(
                ShadowPerformanceSummaryResponse.from(ShadowPerformanceSummaryTestFixtures.validSummary())
        );
        JsonNode json = mapper.readTree(payload);
        List<String> topLevelFields = new ArrayList<>();
        json.fieldNames().forEachRemaining(topLevelFields::add);

        assertThat(topLevelFields).containsExactly(
                "summaryType",
                "summaryVersion",
                "generatedAt",
                "model",
                "governance",
                "evaluation",
                "evaluationPopulation",
                "metrics",
                "disagreementSummary",
                "warnings",
                "limitations",
                "banner"
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
    void staticFixtureProviderNotPresentInMainSource() throws Exception {
        String emptyProvider = Files.readString(PRODUCTION_ROOT.resolve("governance/shadowperformance/EmptyShadowPerformanceSummaryProvider.java"));
        String configuration = Files.readString(PRODUCTION_ROOT.resolve("governance/shadowperformance/ShadowPerformanceSummaryProviderConfiguration.java"));
        String source = shadowPerformanceSource();

        assertThat(PRODUCTION_ROOT.resolve("governance/shadowperformance/StaticShadowPerformanceSummaryProvider.java")).doesNotExist();
        assertThat(source).doesNotContain(
                "StaticShadowPerformanceSummaryProvider",
                "Optional.of(CURRENT_SUMMARY)",
                "private static final ShadowPerformanceSummary CURRENT_SUMMARY"
        );
        assertThat(emptyProvider).contains("Optional.empty()");
        assertThat(emptyProvider).doesNotContain("@Component");
        assertThat(configuration).contains(
                "@ConditionalOnMissingBean(ShadowPerformanceSummaryProvider.class)",
                "emptyShadowPerformanceSummaryProvider"
        );
        assertThat(configuration).contains(
                "@ConditionalOnProperty",
                "shadow-performance.summary.current",
                "artifactBackedShadowPerformanceSummaryProvider"
        );
    }

    @Test
    void productionValidatorUsesContractConstantNotStaticFixtureProvider() throws Exception {
        String validator = Files.readString(PRODUCTION_ROOT.resolve("governance/shadowperformance/ShadowPerformanceSummaryValidator.java"));

        assertThat(validator).contains("ShadowPerformanceSummaryContract.REQUIRED_BANNER");
        assertThat(validator).doesNotContain("StaticShadowPerformanceSummaryProvider.REQUIRED_BANNER");
    }

    @Test
    void testFixturesRemainInTestSourceOnly() throws Exception {
        Path testFixture = ROOT.resolve("alert-service/src/test/java/com/frauddetection/alert/governance/shadowperformance/ShadowPerformanceSummaryTestFixtures.java");

        assertThat(testFixture).exists();
        assertThat(shadowPerformanceSource()).doesNotContain("ShadowPerformanceSummaryTestFixtures");
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
    void fdp108ProviderReadsOnlyConfiguredCurrentSummaryArtifact() throws Exception {
        String source = Files.readString(PRODUCTION_ROOT.resolve(
                "governance/shadowperformance/ArtifactBackedShadowPerformanceSummaryProvider.java"
        ));

        assertThat(source).contains(
                "ShadowPerformanceSummaryCurrentProperties",
                "LinkOption.NOFOLLOW_LINKS",
                "startsWith(baseDir)",
                "objectMapper.readValue",
                "validator.validate(summary)"
        );
        assertThat(source).doesNotContain(
                "DirectoryStream",
                "Files.list",
                "Files.walk",
                "glob",
                "KafkaTemplate",
                "MongoTemplate",
                "RestTemplate",
                "RestClient",
                "WebClient",
                "ModelRegistry",
                "modelArtifact",
                "Payment",
                "FraudCase",
                "AlertRepository",
                "StaticShadowPerformanceSummaryProvider"
        );
    }

    @Test
    void artifactProviderFailsFastOnPrimitiveDefaultingBoundary() throws Exception {
        String source = Files.readString(PRODUCTION_ROOT.resolve(
                "governance/shadowperformance/ArtifactBackedShadowPerformanceSummaryProvider.java"
        ));

        assertThat(source).contains(
                "MapperFeature.ALLOW_COERCION_OF_SCALARS",
                "DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES",
                "DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES",
                "DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES",
                "DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES"
        );
        assertThat(source).doesNotContain(
                "defaultIfMissing",
                "orElse(0)",
                "orElse(false)",
                "precisionAtBudget = 0",
                "recallAtTopK = 0",
                "falsePositiveRate = 0",
                "new ShadowPerformanceSummary("
        );
    }

    @Test
    void fdp108ProviderDoesNotWriteOrMutateOperationalState() throws Exception {
        String source = Files.readString(PRODUCTION_ROOT.resolve(
                "governance/shadowperformance/ArtifactBackedShadowPerformanceSummaryProvider.java"
        ));

        assertThat(source).doesNotContain(
                "Files.write",
                "Files.create",
                "Files.delete",
                "send(",
                "save(",
                "insert(",
                "update(",
                "promote",
                "threshold",
                "retrain",
                "authorizePayment",
                "analystRecommendation"
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
                "/api/v1/governance/shadow-performance/summaries",
                "/api/v1/governance/shadow-performance/search",
                "/api/v1/governance/shadow-performance/history",
                "/api/v1/governance/shadow-performance/promotion",
                "/api/v1/governance/shadow-performance/threshold",
                "/api/v1/governance/shadow-performance/decisioning",
                "/api/v1/governance/shadow-performance/dashboard"
        );
    }

    @Test
    void fdp107DashboardUiUsesOnlyCurrentReadEndpoint() throws Exception {
        String uiSource = uiSource();

        assertThat(uiSource).contains(
                "/api/v1/governance/shadow-performance/summary/current",
                "shadowPerformance",
                "ShadowPerformance"
        );
        assertThat(uiSource).doesNotContain(
                "/api/v1/governance/shadow-performance/summaries",
                "/api/v1/governance/shadow-performance/search",
                "/api/v1/governance/shadow-performance/history",
                "/api/v1/governance/shadow-performance/promotion",
                "/api/v1/governance/shadow-performance/threshold",
                "/api/v1/governance/shadow-performance/decisioning",
                "/api/v1/governance/shadow-performance/dashboard"
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

    @Test
    void docsDescribeFdp108CurrentProviderBoundaries() throws Exception {
        String doc = Files.readString(ROOT.resolve("docs/architecture/shadow_performance_summary_current_provider.md"));

        assertThat(doc).contains(
                "FDP-108 provides the current summary source for FDP-106",
                "read-only",
                "base runtime is fail-closed by default",
                "does not compute metrics",
                "does not recompute shadow performance",
                "does not read raw FDP-102/FDP-103/FDP-104 artifacts",
                "does not expose raw artifacts",
                "Disabled provider or no configured path returns 404",
                "Configured missing artifact returns 503",
                "Unavailable or invalid configured source returns 503",
                "bounded to the configured safe directory",
                "does not allow symlink artifacts",
                "Primitive Defaulting Boundary",
                "missing or null primitive JSON fields",
                "Missing primitive metric field -> 503",
                "Null primitive metric field -> 503",
                "Missing/null governance boolean -> 503",
                "Missing/null evaluation population count -> 503",
                "Missing/null disagreement count -> 503",
                "No silent primitive defaults",
                "No zero substitution",
                "No false substitution",
                "No partial summary",
                "demo fixture metrics are not production current summary",
                "No fake, sample, stale, fallback, or zero metrics",
                "FDP-108 provides a validated current ShadowPerformanceSummary. It does not create model readiness, promotion approval, threshold recommendation, production decisioning approval, payment authorization, or analyst recommendation logic."
        );
    }

    @Test
    void baseDockerComposeDoesNotEnableShadowPerformanceCurrentProviderByDefault() throws Exception {
        String baseCompose = Files.readString(DOCKER_COMPOSE);

        assertThat(baseCompose).contains(
                "SHADOW_PERFORMANCE_SUMMARY_CURRENT_ENABLED: ${SHADOW_PERFORMANCE_SUMMARY_CURRENT_ENABLED:-false}",
                "SHADOW_PERFORMANCE_SUMMARY_CURRENT_BASE_DIR: ${SHADOW_PERFORMANCE_SUMMARY_CURRENT_BASE_DIR:-/run/shadow-performance}",
                "SHADOW_PERFORMANCE_SUMMARY_CURRENT_PATH: ${SHADOW_PERFORMANCE_SUMMARY_CURRENT_PATH:-}"
        );
        assertThat(baseCompose).doesNotContain(
                "SHADOW_PERFORMANCE_SUMMARY_CURRENT_ENABLED: ${SHADOW_PERFORMANCE_SUMMARY_CURRENT_ENABLED:-true}",
                "../ml-inference-service/tests/offline_evaluation/fixtures/shadow_performance",
                "target: /run/shadow-performance/current-summary.json",
                "current-summary.demo.json"
        );
    }

    @Test
    void shadowPerformanceDemoComposeExplicitlyEnablesCurrentProvider() throws Exception {
        String demoCompose = Files.readString(DEMO_COMPOSE);

        assertThat(demoCompose).contains(
                "SHADOW_PERFORMANCE_SUMMARY_CURRENT_ENABLED: \"true\"",
                "SHADOW_PERFORMANCE_SUMMARY_CURRENT_BASE_DIR: /run/shadow-performance",
                "SHADOW_PERFORMANCE_SUMMARY_CURRENT_PATH: /run/shadow-performance/current-summary.demo.json"
        );
    }

    @Test
    void shadowPerformanceDemoComposeUsesClearlyNamedDemoFixture() throws Exception {
        String demoCompose = Files.readString(DEMO_COMPOSE);

        assertThat(demoCompose).contains(
                "local-fixtures/shadow-performance/current-summary.demo.json",
                "current-summary.demo.json"
        );
        assertThat(demoCompose).doesNotContain("ml-inference-service/tests");
    }

    @Test
    void shadowPerformanceDemoComposeMountsFixtureReadOnly() throws Exception {
        String demoCompose = Files.readString(DEMO_COMPOSE);

        assertThat(demoCompose).contains(
                "target: /run/shadow-performance/current-summary.demo.json",
                "read_only: true"
        );
    }

    @Test
    void localDemoLaunchersIncludeShadowPerformanceDemoOverlay() throws Exception {
        String makefile = Files.readString(MAKEFILE);
        String appPs1 = Files.readString(APP_PS1);

        assertThat(makefile).contains("deployment/docker-compose.shadow-performance-demo.yml");
        assertThat(appPs1).contains("\"-f\", \"deployment/docker-compose.shadow-performance-demo.yml\"");
    }

    @Test
    void docsExplainBaseRuntimeAndLocalDemoLauncherBehavior() throws Exception {
        String readme = Files.readString(ROOT.resolve("README.md"));
        String doc = Files.readString(ROOT.resolve("docs/architecture/shadow_performance_summary_current_provider.md"));
        String baseCompose = Files.readString(DOCKER_COMPOSE);

        assertThat(readme).contains(
                "base runtime is fail-closed by default",
                "official local demo launchers include",
                "docker-compose.shadow-performance-demo.yml",
                "demo fixture metrics are not production current summary"
        );
        assertThat(doc).contains(
                "base runtime is fail-closed by default",
                "official local demo launchers include the explicit demo override",
                "If the base Compose file is run without a configured current summary source, the endpoint returns 404",
                "docker-compose.shadow-performance-demo.yml",
                "demo fixture metrics are not production current summary"
        );
        assertThat(baseCompose).doesNotContain(
                "docker-compose.shadow-performance-demo.yml"
        );
    }

    private String shadowPerformanceSource() throws Exception {
        Path packageRoot = PRODUCTION_ROOT.resolve("governance/shadowperformance");
        return Files.walk(packageRoot)
                .filter(path -> path.toString().endsWith(".java"))
                .map(this::readUnchecked)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private String uiSource() throws Exception {
        return Files.walk(UI_ROOT)
                .filter(path -> path.toString().endsWith(".js") || path.toString().endsWith(".jsx"))
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
