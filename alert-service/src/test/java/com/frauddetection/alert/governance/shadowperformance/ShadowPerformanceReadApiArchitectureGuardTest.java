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
    private static final Path GENERATED_COMPOSE = ROOT.resolve("deployment/docker-compose.shadow-performance-generated.yml");
    private static final Path MAKEFILE = ROOT.resolve("Makefile");
    private static final Path APP_PS1 = ROOT.resolve("scripts/app.ps1");
    private static final Path GENERATED_RUNTIME_DOC = ROOT.resolve("docs/architecture/shadow_performance_generated_runtime_override.md");

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
    void fullLocalLaunchersIncludeShadowPerformanceGeneratedOverlay() throws Exception {
        String makefile = Files.readString(MAKEFILE);
        String appPs1 = Files.readString(APP_PS1);

        assertThat(makefile).contains(
                "SECURITY_HARDENED_COMPOSE = $(SHADOW_PERFORMANCE_GENERATED_COMPOSE)",
                "app-up: deployment/.env shadow-performance-summary",
                "app-up-shadow-performance-generated: deployment/.env shadow-performance-summary",
                "deployment/local-generated/shadow-performance/current-summary.json",
                "Generated Shadow Performance Summary not found. Run: make shadow-performance-summary"
        );
        assertThat(appPs1).contains(
                "\"-f\", \"deployment/docker-compose.shadow-performance-generated.yml\"",
                "offline_evaluation.generate_current_shadow_summary",
                "Generated Shadow Performance Summary not found. Run: make shadow-performance-summary"
        );
        assertThat(makeTarget(makefile, "app-up")).doesNotContain("docker-compose.shadow-performance-demo.yml");
        assertThat(appPs1).doesNotContain(
                "\"-f\", \"deployment/docker-compose.shadow-performance-demo.yml\"",
                "entrypoint:",
                "command:",
                "@Scheduled",
                "scheduler",
                "cron",
                "Kafka",
                "promotion readiness",
                "threshold recommendation"
        );
    }

    @Test
    void docsExplainBaseRuntimeAndGeneratedLauncherBehavior() throws Exception {
        String readme = Files.readString(ROOT.resolve("README.md"));
        String doc = Files.readString(ROOT.resolve("docs/architecture/shadow_performance_summary_current_provider.md"));
        String baseCompose = Files.readString(DOCKER_COMPOSE);

        assertThat(readme).contains(
                "base runtime is fail-closed by default",
                "official full local launchers include",
                "Python 3.12+ available as `python`",
                "FDP-110 local startup runs the FDP-109 Python generator before Docker Compose starts",
                "generate the local Shadow Performance Summary artifact before starting",
                "docker-compose.shadow-performance-generated.yml",
                "Generated Shadow Performance Summary not found. Run: make shadow-performance-summary",
                "demo fixture metrics are not production current summary"
        );
        assertThat(doc).contains(
                "base runtime is fail-closed by default",
                "official full local launchers generate the local summary first and include the explicit generated override",
                "If the base Compose file is run without a configured current summary source, the endpoint returns 404",
                "docker-compose.shadow-performance-generated.yml",
                "demo fixture metrics are not production current summary"
        );
        assertThat(baseCompose).doesNotContain(
                "docker-compose.shadow-performance-generated.yml",
                "docker-compose.shadow-performance-demo.yml"
        );
    }

    @Test
    void shadowPerformanceGeneratedComposeExplicitlyMountsGeneratedArtifactReadOnly() throws Exception {
        String generatedCompose = Files.readString(GENERATED_COMPOSE);

        assertThat(GENERATED_COMPOSE).exists();
        assertThat(generatedCompose).contains(
                "SHADOW_PERFORMANCE_SUMMARY_CURRENT_ENABLED: \"true\"",
                "SHADOW_PERFORMANCE_SUMMARY_CURRENT_BASE_DIR: /run/shadow-performance",
                "SHADOW_PERFORMANCE_SUMMARY_CURRENT_PATH: /run/shadow-performance/current-summary.json",
                "./local-generated/shadow-performance/current-summary.json",
                "target: /run/shadow-performance/current-summary.json",
                "read_only: true"
        );
    }

    @Test
    void shadowPerformanceGeneratedComposeDoesNotUseDemoOrRuntimeGeneration() throws Exception {
        String generatedCompose = Files.readString(GENERATED_COMPOSE);

        assertThat(generatedCompose).doesNotContain(
                "current-summary.demo.json",
                "deployment/local-fixtures",
                "local-fixtures",
                "local-demo-inputs",
                "generate_current_shadow_summary",
                "python",
                "entrypoint:",
                "command:",
                "cron",
                "scheduler",
                "Kafka"
        );
    }

    @Test
    void shadowPerformanceDemoAndGeneratedComposeRemainSeparate() throws Exception {
        String baseCompose = Files.readString(DOCKER_COMPOSE);
        String demoCompose = Files.readString(DEMO_COMPOSE);
        String generatedCompose = Files.readString(GENERATED_COMPOSE);
        String makefile = Files.readString(MAKEFILE);

        assertThat(DEMO_COMPOSE).exists();
        assertThat(GENERATED_COMPOSE).exists();
        assertThat(generatedCompose).doesNotContain(
                "docker-compose.shadow-performance-demo.yml",
                "current-summary.demo.json",
                "local-fixtures"
        );
        assertThat(demoCompose).doesNotContain(
                "docker-compose.shadow-performance-generated.yml",
                "local-generated/shadow-performance/current-summary.json",
                "/run/shadow-performance/current-summary.json"
        );
        assertThat(baseCompose).doesNotContain(
                "docker-compose.shadow-performance-generated.yml",
                "local-generated/shadow-performance/current-summary.json",
                "SHADOW_PERFORMANCE_SUMMARY_CURRENT_ENABLED: ${SHADOW_PERFORMANCE_SUMMARY_CURRENT_ENABLED:-true}"
        );
        assertThat(makeTarget(makefile, "app-up-shadow-performance-generated")).doesNotContain(
                "docker-compose.shadow-performance-demo.yml",
                "python -m offline_evaluation.generate_current_shadow_summary"
        );
        assertThat(makeTarget(makefile, "app-up")).contains(
                "deployment/local-generated/shadow-performance/current-summary.json",
                "SECURITY_HARDENED_COMPOSE"
        );
        assertThat(makeTarget(makefile, "app-up")).doesNotContain("docker-compose.shadow-performance-demo.yml");
    }

    @Test
    void makefileHasExplicitGeneratedRuntimeTargetWithFailFastGuidance() throws Exception {
        String makefile = Files.readString(MAKEFILE);
        String generatedTarget = makeTarget(makefile, "app-up-shadow-performance-generated");

        assertThat(makefile).contains(
                "SECURITY_HARDENED_BASE_COMPOSE",
                "SHADOW_PERFORMANCE_DEMO_COMPOSE",
                "SHADOW_PERFORMANCE_GENERATED_COMPOSE",
                "app-up-shadow-performance-generated",
                "shadow-performance-local-loop"
        );
        assertThat(makefile).contains(
                "check-python:",
                "shadow-performance-summary: check-python",
                "Python 3.12+ is required to generate the local Shadow Performance Summary"
        );
        assertThat(generatedTarget).contains(
                "app-up-shadow-performance-generated: deployment/.env shadow-performance-summary",
                "deployment/local-generated/shadow-performance/current-summary.json",
                "Generated Shadow Performance Summary not found. Run: make shadow-performance-summary",
                "SHADOW_PERFORMANCE_GENERATED_COMPOSE",
                "up --build -d"
        );
        assertThat(generatedTarget).doesNotContain(
                "shadow-performance-summary:",
                "python -m offline_evaluation.generate_current_shadow_summary",
                "docker-compose.shadow-performance-demo.yml"
        );
        assertThat(makeTarget(makefile, "shadow-performance-local-loop"))
                .contains("shadow-performance-local-loop: app-up-shadow-performance-generated");
    }

    @Test
    void localLaunchersFailFastWhenHostPythonIsMissing() throws Exception {
        String makefile = Files.readString(MAKEFILE);
        String appPs1 = Files.readString(APP_PS1);
        String message = "Python 3.12+ is required to generate the local Shadow Performance Summary. Install Python and rerun.";

        assertThat(makefile).contains(
                "check-python:",
                "@python --version >/dev/null 2>&1",
                message,
                "shadow-performance-summary: check-python"
        );
        assertThat(appPs1).contains(
                "$pythonCommand = Get-Command python -ErrorAction SilentlyContinue",
                "if ($null -eq $pythonCommand)",
                message,
                "python -m offline_evaluation.generate_current_shadow_summary"
        );
    }

    @Test
    void docsExplainBaseDemoGeneratedRuntimePaths() throws Exception {
        String doc = Files.readString(GENERATED_RUNTIME_DOC);

        assertThat(doc).contains(
                "FDP-110 completes the local generated Shadow Performance runtime loop. It invokes the FDP-109 local generator before Docker Compose starts, mounts the generated current-summary.json artifact into alert-service, lets FDP-108 read it, FDP-106 expose it, and FDP-107 display it.",
                "FDP-110 intentionally combines local generation before Compose, generated runtime mount, and shared global workspace counters as UI context.",
                "FDP-109 owns generation logic.",
                "FDP-110 owns local launcher wiring and runtime mounting.",
                "FDP-108 owns artifact reading.",
                "FDP-106 owns the authorized read API.",
                "FDP-107 owns dashboard display.",
                "Generation before Compose in a local developer launcher is allowed.",
                "Generation inside Docker Compose is forbidden.",
                "Generation inside alert-service runtime is forbidden.",
                "FDP-110 does not generate a Shadow Performance Summary inside Docker Compose or inside the application runtime.",
                "The local developer launcher invokes the FDP-109 generator before `docker compose up`",
                "Base runtime is fail-closed",
                "no configured summary -> 404",
                "Demo runtime uses `docker-compose.shadow-performance-demo.yml`",
                "Demo artifact is separate from generated artifact",
                "Demo artifact is for UI smoke/demo only",
                "Demo artifact is not FDP-109 generated output",
                "Demo artifact is not production current summary",
                "Official local launcher runs FDP-109 generation before Compose.",
                "Generated runtime uses `docker-compose.shadow-performance-generated.yml`",
                "deployment/local-generated/shadow-performance/current-summary.json",
                "Generated runtime exposes the summary through FDP-108/FDP-106/FDP-107.",
                "generated runtime does not use `current-summary.demo.json`",
                "generated runtime does not generate summary inside Docker Compose",
                "Generated Shadow Performance Summary not found. Run: make shadow-performance-summary",
                "Generation happens before Docker Compose starts.",
                "Generation does not happen inside Docker Compose.",
                "Generation does not happen inside alert-service runtime.",
                "FDP-110 does not run the generator inside Docker Compose",
                "FDP-110 does not add a scheduler",
                "FDP-110 does not add cron",
                "FDP-110 does not add a Kafka-triggered job",
                "not promotion readiness",
                "not threshold recommendation",
                "not production decisioning",
                "not payment authorization",
                "not analyst recommendation logic"
        );
    }

    @Test
    void docsExplainSharedGlobalCountersAsShellContextOnly() throws Exception {
        String doc = Files.readString(GENERATED_RUNTIME_DOC);
        String readme = Files.readString(ROOT.resolve("README.md"));

        assertThat(doc).contains(
                "## Shared Global Workspace Counters",
                "Shadow Performance workspace may render shared global workspace counters as shell-level UI context.",
                "These counters are not part of ShadowPerformanceSummary.",
                "They are not model evaluation metrics.",
                "They are not used by FDP-109 generation.",
                "They are not read by FDP-108 provider.",
                "They are not returned by FDP-106 current summary endpoint.",
                "They are not promotion readiness.",
                "They are not threshold recommendation.",
                "They are not production decisioning.",
                "They are not payment authorization.",
                "They are not analyst recommendation logic."
        );
        assertThat(readme).contains(
                "FDP-110 intentionally combines local generation before Compose, generated runtime mount, and shared global workspace counters as UI context.",
                "Global counters in the Shadow Performance workspace are shell-level context only",
                "not part of `ShadowPerformanceSummary`",
                "not model evaluation metrics",
                "not promotion readiness",
                "not threshold recommendation",
                "not production decisioning",
                "not payment authorization",
                "not analyst recommendation logic"
        );
    }

    @Test
    void fdp110UiCountersRemainShellContextAndDoNotEnterSummaryContract() throws Exception {
        String shell = Files.readString(UI_ROOT.resolve("workspace/WorkspaceDashboardShell.jsx"));
        String dashboard = Files.readString(UI_ROOT.resolve("components/ShadowPerformanceDashboard.jsx"));
        String runtime = Files.readString(UI_ROOT.resolve("workspace/ShadowPerformanceWorkspaceRuntime.jsx"));
        String container = Files.readString(UI_ROOT.resolve("workspace/ShadowPerformanceWorkspaceContainer.jsx"));
        String page = Files.readString(UI_ROOT.resolve("pages/ShadowPerformanceDashboardPage.jsx"));

        assertThat(shell).contains(
                "useWorkspaceCounters",
                "workspaceCounters={workspaceCounterState.counters}",
                "workspaceCountersStatus={workspaceCounterState}",
                "showWorkspaceCounters={activeRoute.showWorkspaceCounters !== false}"
        );
        assertThat(runtime).contains(
                "useShadowPerformanceSummary",
                "summaryState={summaryState}"
        );
        assertThat(container).contains(
                "summary={summaryState.summary}",
                "isLoading={summaryState.isLoading}",
                "error={summaryState.error}"
        );
        assertThat(String.join("\n", dashboard, runtime, container, page)).doesNotContain(
                "useWorkspaceCounters",
                "workspaceCounters",
                "workspaceCounterState",
                "setCounterValue",
                "totalFraudCases",
                "totalSuspiciousTransactions"
        );
    }

    @Test
    void generatedRuntimeBridgeDoesNotIntroduceScopeCreepTerms() throws Exception {
        String fdp110Sources = String.join("\n",
                Files.readString(GENERATED_COMPOSE),
                Files.readString(GENERATED_RUNTIME_DOC),
                makeTarget(Files.readString(MAKEFILE), "app-up-shadow-performance-generated")
        );

        assertThat(fdp110Sources).doesNotContain(
                "promotionReadiness",
                "promotion readiness score",
                "recommendedThreshold",
                "thresholdRecommendation",
                "championCandidate",
                "modelRegistry.write",
                "saveModelArtifact",
                "KafkaProducer",
                "KafkaTemplate",
                "@Scheduled",
                "APScheduler",
                "paymentAuthorization",
                "analystRecommendation",
                "approve transaction",
                "decline transaction",
                "block transaction"
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

    private String makeTarget(String makefile, String target) {
        String marker = target + ":";
        int start = makefile.indexOf(marker);
        assertThat(start).as("Makefile target " + target).isGreaterThanOrEqualTo(0);
        int nextTarget = makefile.indexOf("\n\n", start);
        if (nextTarget < 0) {
            return makefile.substring(start);
        }
        return makefile.substring(start, nextTarget);
    }
}
