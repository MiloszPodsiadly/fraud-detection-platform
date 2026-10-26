package com.frauddetection.alert.governance.modelcard;

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
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

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
        ReadAccessResultCountExtractor.class
})
@ActiveProfiles("test")
@TestPropertySource(properties = "app.security.demo-auth.enabled=true")
class ModelCardControllerAuthorizationTest {

    private static final String PATH = "/api/v1/governance/model-card/current";

    @jakarta.annotation.Resource
    private MockMvc mockMvc;

    @MockitoBean
    private ModelCardReadService readService;

    @MockitoBean
    private ReadAccessAuditService readAccessAuditService;

    @MockitoBean
    private AlertServiceMetrics metrics;

    @Test
    void anonymousUserCannotReadModelCard() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());

        verifyNoInteractions(readService);
        verifyNoInteractions(readAccessAuditService);
    }

    @Test
    void unrelatedGovernanceAuthorityCannotReadModelCard() throws Exception {
        mockMvc.perform(get(PATH).with(authentication(auth(AnalystAuthority.PROMOTION_READINESS_READ))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(readService);
        verifyNoInteractions(readAccessAuditService);
    }

    @Test
    void authorizedReadReturnsBoundedCardAndAuditsExactlyOnce() throws Exception {
        ModelCard card = ModelCardTestFixtures.validUnavailableCard(new ObjectMapper());
        when(readService.currentCard()).thenReturn(ModelCardResponse.from(card));

        mockMvc.perform(get(PATH).with(authentication(auth(AnalystAuthority.MODEL_CARD_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("ml-model-card-v1"))
                .andExpect(jsonPath("$.promotionState").value("NOT_EVALUATED_FOR_PROMOTION"))
                .andExpect(jsonPath("$.verifiedUsageBoundary.runtimeDecisionAuthority").value("NONE"));

        verify(readAccessAuditService).auditOrThrow(
                argThat(target -> target.endpointCategory() == ReadAccessEndpointCategory.MODEL_CARD
                        && target.resourceType() == ReadAccessResourceType.MODEL_CARD
                        && target.resourceId() == null),
                eq(ReadAccessAuditOutcome.SUCCESS),
                eq(1),
                eq(null)
        );
    }

    @Test
    void returns404WhenModelCardIsNotConfigured() throws Exception {
        when(readService.currentCard()).thenThrow(new ResponseStatusException(NOT_FOUND, "Model Card not found."));

        mockMvc.perform(get(PATH).with(authentication(auth(AnalystAuthority.MODEL_CARD_READ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Model Card not found."));
    }

    @Test
    void returns503WithoutLeakingArtifactDetails() throws Exception {
        when(readService.currentCard()).thenThrow(
                new ResponseStatusException(SERVICE_UNAVAILABLE, "Model Card unavailable.")
        );

        mockMvc.perform(get(PATH).with(authentication(auth(AnalystAuthority.MODEL_CARD_READ))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Model Card unavailable."));
    }

    private UsernamePasswordAuthenticationToken auth(String... authorities) {
        return new UsernamePasswordAuthenticationToken(
                "governance-admin",
                null,
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()
        );
    }
}
