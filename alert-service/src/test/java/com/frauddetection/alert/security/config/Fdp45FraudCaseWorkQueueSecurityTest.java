package com.frauddetection.alert.security.config;

import com.frauddetection.alert.controller.FraudCaseController;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.api.FraudCaseWorkQueueSliceResponse;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.error.ApiAccessDeniedHandler;
import com.frauddetection.alert.security.error.ApiAuthenticationEntryPoint;
import com.frauddetection.alert.security.error.SecurityErrorResponseWriter;
import com.frauddetection.alert.service.FraudCaseManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FraudCaseController.class)
@Import({
        AlertSecurityConfig.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class,
        SecurityDeniedAccessTelemetrySliceTestConfig.class,
        FraudCaseResponseMapper.class,
        AlertResponseMapper.class,
        AlertServiceExceptionHandler.class
})
class Fdp45FraudCaseWorkQueueSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FraudCaseManagementService fraudCaseManagementService;

    @MockBean
    private AlertServiceMetrics alertServiceMetrics;

    @MockBean
    private SensitiveReadAuditService sensitiveReadAuditService;

    @Test
    void shouldRequireFraudCaseReadForWorkQueueAndKeepReadOnlyUsersFromMutating() throws Exception {
        when(fraudCaseManagementService.workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new FraudCaseWorkQueueSliceResponse(List.of(), 0, 20, false, null));

        mockMvc.perform(get("/api/v1/fraud-cases/work-queue"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.details[0]").value("reason:missing_credentials"));
        mockMvc.perform(get("/api/v1/fraud-cases/work-queue").with(userWith(AnalystAuthority.TRANSACTION_MONITOR_READ)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/fraud-cases/work-queue").with(userWith(AnalystAuthority.FRAUD_CASE_READ)))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .with(userWith(AnalystAuthority.FRAUD_CASE_READ))
                        .header("X-Idempotency-Key", "case-update-readonly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"IN_REVIEW","analystId":"analyst-1","decisionReason":"review","tags":[]}
                                """))
                .andExpect(status().isForbidden());
    }

    private RequestPostProcessor userWith(String... authorities) {
        return authentication(new UsernamePasswordAuthenticationToken(
                "analyst-1",
                null,
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()
        ));
    }
}
