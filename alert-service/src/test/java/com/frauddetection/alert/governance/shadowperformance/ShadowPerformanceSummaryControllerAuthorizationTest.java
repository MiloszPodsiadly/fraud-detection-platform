package com.frauddetection.alert.governance.shadowperformance;

import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessAuditResponseAdvice;
import com.frauddetection.alert.audit.read.ReadAccessAuditService;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResultCountExtractor;
import com.frauddetection.alert.audit.read.ReadAccessAuditClassifier;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
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
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
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

@WebMvcTest(ShadowPerformanceSummaryController.class)
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
class ShadowPerformanceSummaryControllerAuthorizationTest {

    private static final String PATH = "/api/v1/governance/shadow-performance/summary/current";

    @jakarta.annotation.Resource
    private MockMvc mockMvc;

    @MockBean
    private ShadowPerformanceSummaryReadService readService;

    @MockBean
    private ReadAccessAuditService readAccessAuditService;

    @MockBean
    private AlertServiceMetrics metrics;

    @Test
    void anonymousUserCannotReadShadowPerformanceSummary() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(readService);
        verifyNoInteractions(readAccessAuditService);
    }

    @Test
    void userWithoutShadowPerformanceReadCannotReadSummary() throws Exception {
        mockMvc.perform(get(PATH).with(authentication(auth(AnalystAuthority.ALERT_READ))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(readService);
        verifyNoInteractions(readAccessAuditService);
    }

    @Test
    void transactionReadAuthorityAloneCannotReadShadowPerformanceSummary() throws Exception {
        mockMvc.perform(get(PATH).with(authentication(auth(AnalystAuthority.TRANSACTION_MONITOR_READ))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(readService);
    }

    @Test
    void fraudCaseReadAuthorityAloneCannotReadShadowPerformanceSummary() throws Exception {
        mockMvc.perform(get(PATH).with(authentication(auth(AnalystAuthority.FRAUD_CASE_READ))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(readService);
    }

    @Test
    void analystReadAuthorityAloneCannotReadShadowPerformanceSummary() throws Exception {
        mockMvc.perform(get(PATH).with(authentication(auth(AnalystAuthority.ALERT_READ, AnalystAuthority.ASSISTANT_SUMMARY_READ))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(readService);
    }

    @Test
    void authorizedReadAuditsExactlyOnce() throws Exception {
        when(readService.currentSummary()).thenReturn(validResponse());

        mockMvc.perform(get(PATH).with(authentication(auth(AnalystAuthority.SHADOW_PERFORMANCE_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summaryType").value("SHADOW_PERFORMANCE_SUMMARY_V1"))
                .andExpect(jsonPath("$.governance.diagnosticOnly").value(true))
                .andExpect(jsonPath("$.governance.approvedFor[0]").value("COMPARE"))
                .andExpect(jsonPath("$.governance.approvedFor[1]").value("SHADOW"))
                .andExpect(jsonPath("$.evaluationPopulation.datasetRecordsRead").value(5))
                .andExpect(jsonPath("$.metrics.precisionAtBudget").value(0.666667))
                .andExpect(jsonPath("$.banner").value(ShadowPerformanceSummaryContract.REQUIRED_BANNER));

        verify(readAccessAuditService).audit(
                argThat(target -> target.endpointCategory() == ReadAccessEndpointCategory.SHADOW_PERFORMANCE_SUMMARY
                        && target.resourceType() == ReadAccessResourceType.SHADOW_PERFORMANCE_SUMMARY
                        && target.resourceId() == null),
                eq(ReadAccessAuditOutcome.SUCCESS),
                eq(1),
                eq(null)
        );
    }

    @Test
    void returns404WhenSummaryDoesNotExist() throws Exception {
        when(readService.currentSummary()).thenThrow(new ResponseStatusException(NOT_FOUND, "Shadow performance summary not found."));

        mockMvc.perform(get(PATH).with(authentication(auth(AnalystAuthority.SHADOW_PERFORMANCE_READ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Shadow performance summary not found."));
    }

    @Test
    void invalidSummaryReturnsServiceUnavailable() throws Exception {
        when(readService.currentSummary()).thenThrow(new ResponseStatusException(
                SERVICE_UNAVAILABLE,
                "Shadow performance summary is invalid."
        ));

        mockMvc.perform(get(PATH).with(authentication(auth(AnalystAuthority.SHADOW_PERFORMANCE_READ))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Shadow performance summary is invalid."));
    }

    @Test
    void returns503WhenSummaryProviderUnavailable() throws Exception {
        when(readService.currentSummary()).thenThrow(new ResponseStatusException(
                SERVICE_UNAVAILABLE,
                "Shadow performance summary unavailable."
        ));

        mockMvc.perform(get(PATH).with(authentication(auth(AnalystAuthority.SHADOW_PERFORMANCE_READ))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Shadow performance summary unavailable."));
    }

    private ShadowPerformanceSummaryResponse validResponse() {
        return ShadowPerformanceSummaryResponse.from(ShadowPerformanceSummaryTestFixtures.validSummary());
    }

    private UsernamePasswordAuthenticationToken auth(String... authorities) {
        return new UsernamePasswordAuthenticationToken(
                "analyst-1",
                null,
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()
        );
    }
}
