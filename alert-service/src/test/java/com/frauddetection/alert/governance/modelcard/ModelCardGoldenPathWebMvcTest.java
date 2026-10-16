package com.frauddetection.alert.governance.modelcard;

import com.frauddetection.alert.governance.modelevaluation.ModelEvaluationGovernanceValidator;

import com.frauddetection.alert.audit.read.ReadAccessAuditClassifier;
import com.frauddetection.alert.audit.read.ReadAccessAuditResponseAdvice;
import com.frauddetection.alert.audit.read.ReadAccessAuditService;
import com.frauddetection.alert.audit.read.ReadAccessResultCountExtractor;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.auth.AnalystAuthenticationFactory;
import com.frauddetection.alert.security.auth.DemoAuthHeaderParser;
import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.config.AlertSecurityConfig;
import com.frauddetection.alert.security.config.DemoAuthSecurityConfig;
import com.frauddetection.alert.security.config.SecurityDeniedAccessTelemetrySliceTestConfig;
import com.frauddetection.alert.security.error.ApiAccessDeniedHandler;
import com.frauddetection.alert.security.error.ApiAuthenticationEntryPoint;
import com.frauddetection.alert.security.error.SecurityErrorResponseWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URISyntaxException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ModelCardController.class)
@Import({
        AlertSecurityConfig.class,
        DemoAuthSecurityConfig.class,
        AnalystAuthenticationFactory.class,
        DemoAuthHeaderParser.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class,
        SecurityDeniedAccessTelemetrySliceTestConfig.class,
        AlertServiceExceptionHandler.class,
        ReadAccessAuditResponseAdvice.class,
        ReadAccessAuditClassifier.class,
        ReadAccessResultCountExtractor.class,
        ModelCardProviderConfiguration.class,
        ModelCardReadService.class,
        ModelEvaluationGovernanceValidator.class,
        ModelCardReadMetrics.class
})
@ActiveProfiles("test")
class ModelCardGoldenPathWebMvcTest {

    private static final String PATH = "/api/v1/governance/model-card/current";
    private static final String MODEL_SHA =
            "55499538d93a634bb5c5fc2326380d013efaea8b0045320f838cad7405df15b6";
    private static final String AVAILABLE_FIXTURE = "python-generated-available-v1";
    private static final String MIXED_FIXTURE = "python-generated-mixed-v1";
    private static final String NO_DATA_FIXTURE = "python-generated-no-data-v1";
    private static final String PARTIAL_TRUNCATED_FIXTURE = "python-generated-partial-truncated-v1";
    private static final Path FIXTURE_ROOT = fixtureRoot();
    private static final Path RUNTIME_ARTIFACTS = createRuntimeArtifactDirectory();

    @jakarta.annotation.Resource
    private MockMvc mockMvc;

    @MockitoBean
    private ReadAccessAuditService readAccessAuditService;

    @MockitoBean
    private AlertServiceMetrics alertServiceMetrics;

    @DynamicPropertySource
    static void modelCardProperties(DynamicPropertyRegistry registry) {
        registry.add("model-card.current.enabled", () -> true);
        registry.add("model-card.current.base-dir", RUNTIME_ARTIFACTS::toString);
        registry.add("model-card.current.path", () -> RUNTIME_ARTIFACTS.resolve("ml_model_card.json").toString());
    }

    @BeforeEach
    void resetRuntimeArtifacts() throws IOException {
        installFixture(AVAILABLE_FIXTURE);
    }

    private void installFixture(String fixtureName) throws IOException {
        for (String filename : java.util.List.of("ml_model_card.json", "ml_model_card.md", "manifest.json")) {
            Files.copy(
                    FIXTURE_ROOT.resolve(fixtureName).resolve(filename),
                    RUNTIME_ARTIFACTS.resolve(filename),
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    @Test
    void authenticatedReadPreservesPythonGeneratedIdentityAndMetrics() throws Exception {
        mockMvc.perform(get(PATH).with(authentication(authorizedAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluationEvidence.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.subject.modelArtifactSha256").value(MODEL_SHA))
                .andExpect(jsonPath("$.verifiedUsageBoundary.intendedUse[0]")
                        .value("EXACT_MODEL_OFFLINE_EVALUATION"))
                .andExpect(jsonPath("$.verifiedUsageBoundary.notIntendedUse[0]")
                        .value("AUTOMATIC_APPROVE_DECLINE_OR_BLOCK"))
                .andExpect(jsonPath("$.verifiedUsageBoundary.useLimitations[0]")
                        .value("ANALYST_FEEDBACK_IS_REVIEW_SIGNAL_NOT_GROUND_TRUTH"))
                .andExpect(jsonPath("$.evaluationSummary.evaluationSubject.modelArtifactSha256").value(MODEL_SHA))
                .andExpect(jsonPath("$.evaluationSummary.population.recordsEvaluated").value(1))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.mlPredictionMetrics.precision.value")
                        .value(1.0))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.mlPredictionMetrics.falsePositiveRate.reason")
                        .value("NO_ACTUAL_NEGATIVES"));
    }

    @Test
    void mixedPopulationPreservesExactRoundedMetricsRankingAndDisagreement() throws Exception {
        installFixture(MIXED_FIXTURE);

        mockMvc.perform(get(PATH).with(authentication(authorizedAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject.modelArtifactSha256").value(MODEL_SHA))
                .andExpect(jsonPath("$.evaluationSummary.population.recordsEvaluated").value(7))
                .andExpect(jsonPath("$.evaluationSummary.classBalance.positiveClassCount").value(4))
                .andExpect(jsonPath("$.evaluationSummary.classBalance.negativeClassCount").value(3))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.mlPredictionMetrics.truePositive").value(2))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.mlPredictionMetrics.falsePositive").value(1))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.mlPredictionMetrics.trueNegative").value(2))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.mlPredictionMetrics.falseNegative").value(2))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.mlPredictionMetrics.precision.value")
                        .value(0.666667))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.mlPredictionMetrics.recall.value")
                        .value(0.5))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.mlPredictionMetrics.falsePositiveRate.value")
                        .value(0.333333))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.mlPredictionMetrics.falseNegativeRate.value")
                        .value(0.5))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.mlPredictionMetrics.precisionAtK['3'].actualK")
                        .value(3))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.mlPredictionMetrics.precisionAtK['3'].value.value")
                        .value(0.666667))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.mlPredictionMetrics.recallAtK['3'].value.value")
                        .value(0.5))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.mlPredictionMetrics.precisionAtK['7'].actualK")
                        .value(7))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.rulesVsMlDisagreement.categoryCounts.BOTH_HIGH")
                        .value(1))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.rulesVsMlDisagreement.categoryCounts.BOTH_LOW")
                        .value(2))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.rulesVsMlDisagreement.categoryCounts.ML_HIGH_RULES_LOW")
                        .value(2))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.rulesVsMlDisagreement.categoryCounts.RULES_HIGH_ML_LOW")
                        .value(2))
                .andExpect(jsonPath("$.evaluationSummary.warnings").isEmpty());
    }

    @Test
    void boundaryPopulationPreservesUnavailablePartialTruncatedAndNumericZeroSemantics() throws Exception {
        installFixture(NO_DATA_FIXTURE);

        mockMvc.perform(get(PATH).with(authentication(authorizedAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluationSummary.population.recordsEvaluated").value(0))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.mlPredictionMetrics.precision.available")
                        .value(false))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.mlPredictionMetrics.precision.reason")
                        .value("NO_PREDICTED_POSITIVES"))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.mlPredictionMetrics.precision.value")
                        .value(nullValue()))
                .andExpect(jsonPath("$.evaluationSummary.warnings[0]")
                        .value("INSUFFICIENT_MODEL_LINEAGE_RECORDS"))
                .andExpect(jsonPath("$.evaluationSummary.warnings[1]")
                        .value("MODEL_PREDICTION_SIGNAL_UNAVAILABLE"));

        installFixture(PARTIAL_TRUNCATED_FIXTURE);

        mockMvc.perform(get(PATH).with(authentication(authorizedAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluationSummary.population.recordsConsidered").value(3))
                .andExpect(jsonPath("$.evaluationSummary.population.recordsEvaluated").value(2))
                .andExpect(jsonPath("$.evaluationSummary.population.recordsExcludedIdentityMismatch").value(1))
                .andExpect(jsonPath("$.evaluationSummary.sourceDataset.truncated").value(true))
                .andExpect(jsonPath("$.evaluationSummary.warnings[0]")
                        .value("MODEL_EVALUATION_PARTIAL_COVERAGE"))
                .andExpect(jsonPath("$.evaluationSummary.warnings[1]").value("SOURCE_DATASET_TRUNCATED"))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.mlPredictionMetrics.falsePositiveRate.available")
                        .value(true))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.mlPredictionMetrics.falsePositiveRate.value")
                        .value(0.0))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.mlPredictionMetrics.falseNegativeRate.available")
                        .value(true))
                .andExpect(jsonPath("$.evaluationSummary.supportedMetrics.mlPredictionMetrics.falseNegativeRate.value")
                        .value(0.0));
    }

    @Test
    void configuredInvalidArtifactReturnsSafe503() throws Exception {
        Files.writeString(
                RUNTIME_ARTIFACTS.resolve("ml_model_card.md"),
                "tampered\n",
                StandardCharsets.UTF_8
        );

        mockMvc.perform(get(PATH).with(authentication(authorizedAuthentication())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Model Card unavailable."));
    }

    @AfterAll
    static void deleteRuntimeArtifacts() throws IOException {
        for (String filename : java.util.List.of("ml_model_card.json", "ml_model_card.md", "manifest.json")) {
            Files.deleteIfExists(RUNTIME_ARTIFACTS.resolve(filename));
        }
        Files.deleteIfExists(RUNTIME_ARTIFACTS);
    }

    private static UsernamePasswordAuthenticationToken authorizedAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                "governance-admin",
                null,
                java.util.List.of(new SimpleGrantedAuthority(AnalystAuthority.MODEL_CARD_READ))
        );
    }

    private static Path fixtureRoot() {
        try {
            return Path.of(Objects.requireNonNull(ModelCardGoldenPathWebMvcTest.class.getResource(
                    "/fixtures/model-card/python-generated-available-v1/ml_model_card.json"
            )).toURI()).getParent().getParent();
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Golden Model Card fixture root unavailable", exception);
        }
    }

    private static Path createRuntimeArtifactDirectory() {
        try {
            return Files.createTempDirectory("model-card-golden-runtime-");
        } catch (IOException exception) {
            throw new IllegalStateException("Model Card runtime fixture directory unavailable", exception);
        }
    }
}
