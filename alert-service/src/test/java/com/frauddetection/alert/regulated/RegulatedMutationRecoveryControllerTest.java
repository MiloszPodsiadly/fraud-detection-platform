package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("production-readiness")
@Tag("integration")
class RegulatedMutationRecoveryControllerTest {

    @Test
    void apiReportsLongRunningProcessingOperationally() throws Exception {
        RegulatedMutationRecoveryService service = mock(RegulatedMutationRecoveryService.class);
        when(service.inspect("idem-long-running")).thenReturn(new RegulatedMutationCommandInspectionResponse(
                "96e6f95f0d3c51986336fb4eb7074b28ba1a765241b3853b779a0731b69a535b",
                "idem-l...ning",
                "SUBMIT_ANALYST_DECISION",
                "ALERT",
                "alert-1",
                "AUDIT_ATTEMPTED",
                "PROCESSING",
                "owner-1",
                Instant.parse("2026-05-05T18:10:00Z"),
                1,
                true,
                "attempted-audit",
                null,
                null,
                "LONG_RUNNING_PROCESSING",
                null,
                Instant.parse("2026-05-05T18:00:00Z")
        ));

        mockMvc(service)
                .perform(get("/api/v1/regulated-mutations/idem-long-running")
                        .principal(new TestingAuthenticationToken("ops-admin", "n/a", "FRAUD_OPS_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("AUDIT_ATTEMPTED"))
                .andExpect(jsonPath("$.execution_status").value("PROCESSING"))
                .andExpect(jsonPath("$.lease_expires_at").exists())
                .andExpect(jsonPath("$.lease_renewal_count").value(1))
                .andExpect(jsonPath("$.lease_owner").doesNotExist())
                .andExpect(jsonPath("$.lease_owner_present").value(true))
                .andExpect(jsonPath("$.lease_owner_hash").exists())
                .andExpect(jsonPath("$.lease_owner_hash").value(not("owner-1")))
                .andExpect(jsonPath("$.degradation_reason").value("LONG_RUNNING_PROCESSING"))
                .andExpect(jsonPath("$.idempotency_key_masked").value("idem-l...ning"))
                .andExpect(jsonPath("$.idempotency_key_masked").value(not("idem-long-running")));
    }

    @Test
    void apiDoesNotTreatCheckpointRenewalAsProgress() throws Exception {
        RegulatedMutationRecoveryService service = mock(RegulatedMutationRecoveryService.class);
        when(service.inspect("idem-checkpoint-only")).thenReturn(new RegulatedMutationCommandInspectionResponse(
                "86e6f95f0d3c51986336fb4eb7074b28ba1a765241b3853b779a0731b69a535c",
                "idem-c...only",
                "SUBMIT_ANALYST_DECISION",
                "ALERT",
                "alert-2",
                "REQUESTED",
                "PROCESSING",
                "owner-2",
                Instant.parse("2026-05-05T18:15:00Z"),
                1,
                false,
                null,
                null,
                null,
                null,
                null,
                Instant.parse("2026-05-05T18:00:00Z")
        ));

        mockMvc(service)
                .perform(get("/api/v1/regulated-mutations/idem-checkpoint-only")
                        .principal(new TestingAuthenticationToken("ops-admin", "n/a", "FRAUD_OPS_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REQUESTED"))
                .andExpect(jsonPath("$.execution_status").value("PROCESSING"))
                .andExpect(jsonPath("$.lease_renewal_count").value(1))
                .andExpect(jsonPath("$.response_snapshot_present").value(false))
                .andExpect(jsonPath("$.attempted_audit_id").doesNotExist())
                .andExpect(jsonPath("$.success_audit_id").doesNotExist());
    }

    @Test
    void inspectionEndpointNeverReturnsRawSensitiveFieldsOrExceptionText() throws Exception {
        RegulatedMutationRecoveryService service = mock(RegulatedMutationRecoveryService.class);
        when(service.inspect("idem-sensitive")).thenReturn(new RegulatedMutationCommandInspectionResponse(
                "raw-request-hash-must-not-appear",
                "idem-s...tive",
                "SUBMIT_ANALYST_DECISION",
                "ALERT",
                "alert-sensitive",
                "BUSINESS_COMMITTING",
                "RECOVERY_REQUIRED",
                "raw-owner-sensitive",
                Instant.parse("2026-05-05T18:15:00Z"),
                2,
                true,
                "attempted-audit",
                null,
                null,
                "RECOVERY_REQUIRED",
                "raw exception at /api/v1/regulated-mutations token=secret",
                Instant.parse("2026-05-05T18:00:00Z")
        ));

        mockMvc(service)
                .perform(get("/api/v1/regulated-mutations/idem-sensitive")
                        .principal(new TestingAuthenticationToken("ops-admin", "n/a", "FRAUD_OPS_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotency_key").doesNotExist())
                .andExpect(jsonPath("$.request_hash").doesNotExist())
                .andExpect(jsonPath("$.intent_hash").doesNotExist())
                .andExpect(jsonPath("$.payload_hash").doesNotExist())
                .andExpect(jsonPath("$.lease_owner").doesNotExist())
                .andExpect(jsonPath("$.lease_owner_present").value(true))
                .andExpect(jsonPath("$.lease_owner_hash").exists())
                .andExpect(jsonPath("$.lease_owner_hash").value(not("raw-owner-sensitive")))
                .andExpect(jsonPath("$.last_error").doesNotExist())
                .andExpect(jsonPath("$.last_error_code").value("UNSAFE_ERROR_REDACTED"))
                .andExpect(content().string(not(containsString("raw exception"))))
                .andExpect(content().string(not(containsString("/api/v1"))))
                .andExpect(content().string(not(containsString("token=secret"))));
    }

    private MockMvc mockMvc(RegulatedMutationRecoveryService service) {
        RegulatedMutationRecoveryController controller = new RegulatedMutationRecoveryController(
                service,
                new RegulatedMutationInspectionRateLimiter(30),
                mock(SensitiveReadAuditService.class)
        );
        return MockMvcBuilders.standaloneSetup(controller)
                .defaultRequest(get("/").with(request -> {
                    request.setRemoteAddr("127.0.0.1");
                    return request;
                }))
                .build();
    }
}
