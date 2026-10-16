package com.frauddetection.alert.governance.exactmodelshadowperformance;

import com.frauddetection.alert.audit.read.ReadAccessAuditClassifier;
import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessAuditResponseAdvice;
import com.frauddetection.alert.audit.read.ReadAccessAuditService;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
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
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExactModelShadowPerformanceController.class)
@Import({
        AlertSecurityConfig.class, DemoAuthSecurityConfig.class, AnalystAuthenticationFactory.class,
        DemoAuthHeaderParser.class, ApiAuthenticationEntryPoint.class, ApiAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class, SecurityDeniedAccessTelemetrySliceTestConfig.class,
        AlertServiceExceptionHandler.class, ReadAccessAuditResponseAdvice.class,
        ReadAccessAuditClassifier.class, ReadAccessResultCountExtractor.class
})
@ActiveProfiles("test")
@TestPropertySource(properties = "app.security.demo-auth.enabled=true")
class ExactModelShadowPerformanceControllerAuthorizationTest {

    private static final String PATH = "/api/v1/governance/model-shadow-performance";
    private static final String SHA = "a".repeat(64);

    @jakarta.annotation.Resource
    private MockMvc mockMvc;

    @MockitoBean
    private ExactModelShadowPerformanceReadService readService;

    @MockitoBean
    private ReadAccessAuditService readAccessAuditService;

    @MockitoBean
    private AlertServiceMetrics metrics;

    @Test
    void anonymousAndUnrelatedAuthorityCannotReadArtifact() throws Exception {
        mockMvc.perform(request()).andExpect(status().isUnauthorized());
        mockMvc.perform(request().with(authentication(auth(AnalystAuthority.MODEL_CARD_READ))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(readService, readAccessAuditService);
    }

    @Test
    void authorizedExactReadIsAuditedWithArtifactIdentity() throws Exception {
        when(readService.find(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ObjectMapper().readTree("{\"schemaVersion\":\"exact-model-shadow-performance-v1\"}"));

        mockMvc.perform(request().with(authentication(auth(AnalystAuthority.MODEL_SHADOW_PERFORMANCE_READ))))
                .andExpect(status().isOk());

        verify(readAccessAuditService).audit(
                argThat(target -> target.endpointCategory() == ReadAccessEndpointCategory.EXACT_MODEL_SHADOW_PERFORMANCE
                        && target.resourceType() == ReadAccessResourceType.EXACT_MODEL_SHADOW_PERFORMANCE
                        && SHA.equals(target.resourceId())
                        && target.queryHash() != null),
                eq(ReadAccessAuditOutcome.SUCCESS), eq(1), eq(null)
        );
    }

    @Test
    void malformedExactSelectorReturnsBadRequestWithoutProviderRead() throws Exception {
        mockMvc.perform(get(PATH)
                        .param("modelName", "model")
                        .param("modelVersion", "version")
                        .param("featureContractVersion", "feature-v2")
                        .param("modelArtifactSha256", "invalid")
                        .with(authentication(auth(AnalystAuthority.MODEL_SHADOW_PERFORMANCE_READ))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(readService);
    }

    @Test
    void missingExactArtifactReturnsNotFound() throws Exception {
        when(readService.find(org.mockito.ArgumentMatchers.any())).thenThrow(
                new org.springframework.web.server.ResponseStatusException(
                        NOT_FOUND, "Exact model Shadow Performance artifact not found."));

        mockMvc.perform(request().with(authentication(auth(AnalystAuthority.MODEL_SHADOW_PERFORMANCE_READ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("Exact model Shadow Performance artifact not found."));
    }

    @Test
    void unavailableExactArtifactReturnsServiceUnavailable() throws Exception {
        when(readService.find(org.mockito.ArgumentMatchers.any())).thenThrow(
                new org.springframework.web.server.ResponseStatusException(
                        SERVICE_UNAVAILABLE, "Exact model Shadow Performance artifact unavailable."));

        mockMvc.perform(request().with(authentication(auth(AnalystAuthority.MODEL_SHADOW_PERFORMANCE_READ))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value("Exact model Shadow Performance artifact unavailable."));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request() {
        return get(PATH)
                .param("modelName", "python-logistic-fraud-model")
                .param("modelVersion", "2026-10-15.shadow")
                .param("featureContractVersion", "feature-contract-v2")
                .param("modelArtifactSha256", SHA)
                .param("fromInclusive", "2026-05-01T00:00:00Z")
                .param("toInclusive", "2026-06-01T00:00:00Z");
    }

    private UsernamePasswordAuthenticationToken auth(String... authorities) {
        return new UsernamePasswordAuthenticationToken(
                "governance-admin", null,
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()
        );
    }
}
