package com.frauddetection.alert.governance.shadowperformance;

import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;
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
        AlertServiceExceptionHandler.class
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
    private SensitiveReadAuditService sensitiveReadAuditService;

    @MockBean
    private AlertServiceMetrics metrics;

    @Test
    void anonymousUserCannotReadShadowPerformanceSummary() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(readService);
        verifyNoInteractions(sensitiveReadAuditService);
    }

    @Test
    void userWithoutShadowPerformanceReadCannotReadSummary() throws Exception {
        mockMvc.perform(get(PATH).with(authentication(auth(AnalystAuthority.ALERT_READ))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(readService);
        verifyNoInteractions(sensitiveReadAuditService);
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
    void userWithShadowPerformanceReadCanReadSummary() throws Exception {
        when(readService.currentSummary()).thenReturn(validResponse());

        mockMvc.perform(get(PATH).with(authentication(auth(AnalystAuthority.SHADOW_PERFORMANCE_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summaryType").value("SHADOW_PERFORMANCE_SUMMARY_V1"))
                .andExpect(jsonPath("$.governance.diagnosticOnly").value(true))
                .andExpect(jsonPath("$.governance.approvedFor[0]").value("COMPARE"))
                .andExpect(jsonPath("$.governance.approvedFor[1]").value("SHADOW"))
                .andExpect(jsonPath("$.evaluationPopulation.datasetRecordsRead").value(5))
                .andExpect(jsonPath("$.metrics.precisionAtBudget").value(0.666667))
                .andExpect(jsonPath("$.banner").value(StaticShadowPerformanceSummaryProvider.REQUIRED_BANNER));

        verify(sensitiveReadAuditService).audit(
                org.mockito.ArgumentMatchers.eq(ReadAccessEndpointCategory.SHADOW_PERFORMANCE_SUMMARY),
                org.mockito.ArgumentMatchers.eq(ReadAccessResourceType.SHADOW_PERFORMANCE_SUMMARY),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(1),
                any()
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
    void returns422WhenSummaryInvalid() throws Exception {
        when(readService.currentSummary()).thenThrow(new ResponseStatusException(
                UNPROCESSABLE_ENTITY,
                "Shadow performance summary is invalid."
        ));

        mockMvc.perform(get(PATH).with(authentication(auth(AnalystAuthority.SHADOW_PERFORMANCE_READ))))
                .andExpect(status().isUnprocessableEntity())
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
        return ShadowPerformanceSummaryResponse.from(new StaticShadowPerformanceSummaryProvider().currentSummary().orElseThrow());
    }

    private UsernamePasswordAuthenticationToken auth(String... authorities) {
        return new UsernamePasswordAuthenticationToken(
                "analyst-1",
                null,
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()
        );
    }
}
