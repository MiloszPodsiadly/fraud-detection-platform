package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.config.AlertSecurityConfig;
import com.frauddetection.alert.security.error.ApiAccessDeniedHandler;
import com.frauddetection.alert.security.error.ApiAuthenticationEntryPoint;
import com.frauddetection.alert.security.error.SecurityErrorResponseWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RegulatedMutationRecoveryController.class)
@Import({
        AlertSecurityConfig.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class
})
@ActiveProfiles("test")
class RegulatedMutationRecoveryInspectionGovernanceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RegulatedMutationRecoveryService recoveryService;

    @MockBean
    private RegulatedMutationInspectionRateLimiter inspectionRateLimiter;

    @MockBean
    private SensitiveReadAuditService sensitiveReadAuditService;

    @MockBean
    private AlertServiceMetrics metrics;

    @Test
    void unauthenticatedInspectionRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/regulated-mutations/idem-sensitive"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedNonAdminInspectionRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/regulated-mutations/idem-sensitive")
                        .with(authentication(authenticationWith(AnalystAuthority.ALERT_READ))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminInspectionRequestIsMaskedAuditedAndBounded() throws Exception {
        when(inspectionRateLimiter.allow(any())).thenReturn(true);
        when(recoveryService.inspect("idem-sensitive")).thenReturn(new RegulatedMutationCommandInspectionResponse(
                "96e6f95f0d3c51986336fb4eb7074b28ba1a765241b3853b779a0731b69a535b",
                "idem-s...tive",
                "SUBMIT_ANALYST_DECISION",
                "ALERT",
                "alert-sensitive",
                "BUSINESS_COMMITTING",
                "RECOVERY_REQUIRED",
                "lease-owner-sensitive",
                Instant.parse("2026-05-06T10:00:00Z"),
                2,
                true,
                "audit-attempted",
                null,
                null,
                "RECOVERY_REQUIRED",
                "raw exception stack trace token=secret /api/v1/regulated-mutations",
                Instant.parse("2026-05-06T09:59:00Z")
        ));

        mockMvc.perform(get("/api/v1/regulated-mutations/idem-sensitive")
                        .with(authentication(authenticationWith(AnalystAuthority.REGULATED_MUTATION_RECOVER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("BUSINESS_COMMITTING"))
                .andExpect(jsonPath("$.execution_status").value("RECOVERY_REQUIRED"))
                .andExpect(jsonPath("$.idempotency_key").doesNotExist())
                .andExpect(jsonPath("$.idempotency_key_masked").value("idem-s...tive"))
                .andExpect(jsonPath("$.request_hash").doesNotExist())
                .andExpect(jsonPath("$.intent_hash").doesNotExist())
                .andExpect(jsonPath("$.lease_owner").doesNotExist())
                .andExpect(jsonPath("$.lease_owner_present").value(true))
                .andExpect(jsonPath("$.lease_owner_hash").exists())
                .andExpect(jsonPath("$.resource_id").doesNotExist())
                .andExpect(jsonPath("$.resource_id_hash").exists())
                .andExpect(jsonPath("$.lease_renewal_count").value(2))
                .andExpect(jsonPath("$.degradation_reason").value("RECOVERY_REQUIRED"))
                .andExpect(jsonPath("$.last_error").doesNotExist())
                .andExpect(jsonPath("$.last_error_code").value("UNSAFE_ERROR_REDACTED"))
                .andExpect(content().string(not(containsString("lease-owner-sensitive"))))
                .andExpect(content().string(not(containsString("alert-sensitive"))))
                .andExpect(content().string(not(containsString("raw exception"))))
                .andExpect(content().string(not(containsString("stack trace"))))
                .andExpect(content().string(not(containsString("token=secret"))));

        verify(sensitiveReadAuditService).audit(
                eq(com.frauddetection.alert.audit.read.ReadAccessEndpointCategory.REGULATED_MUTATION_INSPECTION),
                eq(com.frauddetection.alert.audit.read.ReadAccessResourceType.REGULATED_MUTATION_COMMAND),
                eq("96e6f95f0d3c51986336fb4eb7074b28ba1a765241b3853b779a0731b69a535b"),
                eq(1),
                any()
        );
    }

    private TestingAuthenticationToken authenticationWith(String authority) {
        TestingAuthenticationToken token = new TestingAuthenticationToken(
                "ops-admin",
                "n/a",
                List.of(new SimpleGrantedAuthority(authority))
        );
        token.setAuthenticated(true);
        return token;
    }
}
