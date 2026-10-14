package com.frauddetection.alert.governance.modelcard;

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
        ModelCardValidator.class,
        ModelCardReadMetrics.class
})
@ActiveProfiles("test")
class ModelCardGoldenPathWebMvcTest {

    private static final String PATH = "/api/v1/governance/model-card/current";
    private static final String MODEL_SHA =
            "55499538d93a634bb5c5fc2326380d013efaea8b0045320f838cad7405df15b6";
    private static final Path GOLDEN_SOURCE = goldenCard().getParent();
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
        for (String filename : java.util.List.of("ml_model_card.json", "ml_model_card.md", "manifest.json")) {
            Files.copy(
                    GOLDEN_SOURCE.resolve(filename),
                    RUNTIME_ARTIFACTS.resolve(filename),
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    @Test
    void authenticatedReadPreservesPythonGeneratedIdentityAndMetrics() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(
                "governance-admin",
                null,
                java.util.List.of(new SimpleGrantedAuthority(AnalystAuthority.MODEL_CARD_READ))
        );

        mockMvc.perform(get(PATH).with(authentication(authentication)))
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
    void configuredInvalidArtifactReturnsSafe503() throws Exception {
        Files.writeString(
                RUNTIME_ARTIFACTS.resolve("ml_model_card.md"),
                "tampered\n",
                StandardCharsets.UTF_8
        );
        var authentication = new UsernamePasswordAuthenticationToken(
                "governance-admin",
                null,
                java.util.List.of(new SimpleGrantedAuthority(AnalystAuthority.MODEL_CARD_READ))
        );

        mockMvc.perform(get(PATH).with(authentication(authentication)))
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

    private static Path goldenCard() {
        try {
            return Path.of(Objects.requireNonNull(ModelCardGoldenPathWebMvcTest.class.getResource(
                    "/fixtures/model-card/python-generated-available-v1/ml_model_card.json"
            )).toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Golden Model Card fixture path unavailable", exception);
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
