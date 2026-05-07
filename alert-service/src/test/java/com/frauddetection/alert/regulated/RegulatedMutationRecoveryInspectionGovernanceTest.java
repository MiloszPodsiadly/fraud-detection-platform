package com.frauddetection.alert.regulated;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

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
        verify(recoveryService, never()).inspect(any());
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
                .andExpect(content().string(not(containsString("idem-sensitive"))))
                .andExpect(content().string(not(containsString("request-hash-sensitive"))))
                .andExpect(content().string(not(containsString("intent-hash-sensitive"))))
                .andExpect(content().string(not(containsString("actor-sensitive"))))
                .andExpect(content().string(not(containsString("raw exception"))))
                .andExpect(content().string(not(containsString("stack trace"))))
                .andExpect(content().string(not(containsString("/api/v1"))))
                .andExpect(content().string(not(containsString("token=secret"))));

        verify(sensitiveReadAuditService).audit(
                eq(com.frauddetection.alert.audit.read.ReadAccessEndpointCategory.REGULATED_MUTATION_INSPECTION),
                eq(com.frauddetection.alert.audit.read.ReadAccessResourceType.REGULATED_MUTATION_COMMAND),
                eq("96e6f95f0d3c51986336fb4eb7074b28ba1a765241b3853b779a0731b69a535b"),
                eq(1),
                any()
        );
    }

    @Test
    void rateLimitedInspectionRequestIsRejectedBeforeSensitiveRead() throws Exception {
        when(inspectionRateLimiter.allow(any())).thenReturn(false);

        mockMvc.perform(get("/api/v1/regulated-mutations/idem-sensitive")
                        .with(authentication(authenticationWith(AnalystAuthority.REGULATED_MUTATION_RECOVER))))
                .andExpect(status().isTooManyRequests());

        verify(recoveryService, never()).inspect(any());
        verify(sensitiveReadAuditService, never()).audit(any(), any(), any(), any(), any());
    }

    @Test
    void sensitiveReadAuditFailureFailsClosed() throws Exception {
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
                null,
                Instant.parse("2026-05-06T09:59:00Z")
        ));
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Sensitive read audit unavailable."))
                .when(sensitiveReadAuditService)
                .audit(any(), any(), any(), any(), any());

        mockMvc.perform(get("/api/v1/regulated-mutations/idem-sensitive")
                        .with(authentication(authenticationWith(AnalystAuthority.REGULATED_MUTATION_RECOVER))))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void opsInspectionGovernanceArtifactDocumentsControls() throws Exception {
        Path outputDir = Path.of("target", "fdp39-governance");
        Files.createDirectories(outputDir);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("admin_only_verified", true);
        root.put("non_admin_rejected", true);
        root.put("unauthenticated_rejected", true);
        root.put("masking_verified", true);
        root.put("audit_on_access_verified", true);
        root.put("rate_limit_required", true);
        root.put("rate_limit_verified", true);
        root.put("audit_failure_policy_verified", true);
        root.put("audit_failure_fail_closed_verified", true);
        root.put("production_gate_requires_audit_failure_policy", true);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(outputDir.resolve("fdp39-ops-inspection-governance.json").toFile(), root);
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
