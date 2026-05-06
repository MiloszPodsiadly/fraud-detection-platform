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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@Tag("recovery-proof")
class RegulatedMutationPostRestartApiBehaviorTest {

    @Test
    void recoveryRequiredWithStaleSnapshot_afterRestartReturnsRecoveryNotSuccess() throws Exception {
        RegulatedMutationRecoveryService service = service("idem-recovery", response(
                "RECOVERY_REQUIRED",
                "RECOVERY_REQUIRED",
                true,
                "RECOVERY_REQUIRED",
                null
        ));

        mockMvc(service).perform(inspect("idem-recovery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution_status").value("RECOVERY_REQUIRED"))
                .andExpect(jsonPath("$.response_snapshot_present").value(true))
                .andExpect(content().string(not(containsString("COMMITTED_EVIDENCE_PENDING"))))
                .andExpect(content().string(not(containsString("COMMITTED_EVIDENCE_CONFIRMED"))));
    }

    @Test
    void finalizeRecoveryRequiredWithStaleSnapshot_afterRestartReturnsRecoveryNotFinalized() throws Exception {
        RegulatedMutationRecoveryService service = service("idem-finalize-recovery", response(
                "FINALIZE_RECOVERY_REQUIRED",
                "RECOVERY_REQUIRED",
                true,
                "FINALIZE_RECOVERY_REQUIRED",
                null
        ));

        mockMvc(service).perform(inspect("idem-finalize-recovery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FINALIZE_RECOVERY_REQUIRED"))
                .andExpect(jsonPath("$.response_snapshot_present").value(true))
                .andExpect(content().string(not(containsString("FINALIZED_EVIDENCE_PENDING_EXTERNAL"))))
                .andExpect(content().string(not(containsString("FINALIZED_EVIDENCE_CONFIRMED"))));
    }

    @Test
    void processingExpiredAfterRestartReturnsObservableStateNotSuccess() throws Exception {
        RegulatedMutationRecoveryService service = service("idem-expired", response(
                "AUDIT_ATTEMPTED",
                "PROCESSING",
                false,
                "EXPIRED_PROCESSING_AFTER_RESTART",
                "owner-after-kill"
        ));

        mockMvc(service).perform(inspect("idem-expired"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("AUDIT_ATTEMPTED"))
                .andExpect(jsonPath("$.execution_status").value("PROCESSING"))
                .andExpect(jsonPath("$.lease_owner").doesNotExist())
                .andExpect(jsonPath("$.lease_owner_present").value(true))
                .andExpect(jsonPath("$.response_snapshot_present").value(false))
                .andExpect(content().string(not(containsString("COMMITTED_EVIDENCE_CONFIRMED"))));
    }

    @Test
    void successAuditPendingAfterRestartReportsAuditRetryOnly() throws Exception {
        RegulatedMutationRecoveryService service = service("idem-success-audit", response(
                "SUCCESS_AUDIT_PENDING",
                "PROCESSING",
                true,
                "SUCCESS_AUDIT_RETRY_ONLY",
                "owner-success-audit"
        ));

        mockMvc(service).perform(inspect("idem-success-audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCESS_AUDIT_PENDING"))
                .andExpect(jsonPath("$.response_snapshot_present").value(true))
                .andExpect(jsonPath("$.success_audit_id").doesNotExist())
                .andExpect(jsonPath("$.degradation_reason").value("SUCCESS_AUDIT_RETRY_ONLY"));
    }

    @Test
    void finalizedPendingExternalAfterRestartReturnsPendingNotConfirmed() throws Exception {
        RegulatedMutationRecoveryService service = service("idem-pending-external", response(
                "FINALIZED_EVIDENCE_PENDING_EXTERNAL",
                "COMPLETED",
                true,
                null,
                null
        ));

        mockMvc(service).perform(inspect("idem-pending-external"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FINALIZED_EVIDENCE_PENDING_EXTERNAL"))
                .andExpect(jsonPath("$.execution_status").value("COMPLETED"))
                .andExpect(content().string(not(containsString("FINALIZED_EVIDENCE_CONFIRMED"))));
    }

    @Test
    void outboxConfirmationUnknownAfterRestartReturnsExplicitAmbiguity() throws Exception {
        RegulatedMutationRecoveryService service = service("idem-outbox-unknown", response(
                "BUSINESS_COMMITTING",
                "RECOVERY_REQUIRED",
                true,
                "OUTBOX_CONFIRMATION_UNKNOWN",
                null
        ));

        mockMvc(service).perform(inspect("idem-outbox-unknown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution_status").value("RECOVERY_REQUIRED"))
                .andExpect(jsonPath("$.degradation_reason").value("OUTBOX_CONFIRMATION_UNKNOWN"))
                .andExpect(content().string(not(containsString("PUBLISHED_SUCCESS"))));
    }

    @Test
    void missingLocalSuccessAuditAfterFinalizeRequiresRecovery() throws Exception {
        RegulatedMutationRecoveryService service = service("idem-missing-local-success", response(
                "FINALIZING",
                "RECOVERY_REQUIRED",
                true,
                "LOCAL_SUCCESS_AUDIT_MISSING",
                null
        ));

        mockMvc(service).perform(inspect("idem-missing-local-success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FINALIZING"))
                .andExpect(jsonPath("$.execution_status").value("RECOVERY_REQUIRED"))
                .andExpect(jsonPath("$.success_audit_id").doesNotExist())
                .andExpect(content().string(not(containsString("FINALIZED_EVIDENCE_CONFIRMED"))));
    }

    @Test
    void inspectionAfterRestartDoesNotExposeRawSensitiveFields() throws Exception {
        RegulatedMutationRecoveryService service = service("idem-sensitive-after-restart", responseWithLastErrorCode(
                "BUSINESS_COMMITTING",
                "RECOVERY_REQUIRED",
                true,
                "RECOVERY_REQUIRED",
                "owner-sensitive-after-restart",
                "UNSAFE_ERROR_REDACTED"
        ));

        mockMvc(service).perform(inspect("idem-sensitive-after-restart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotency_key").doesNotExist())
                .andExpect(jsonPath("$.request_hash").doesNotExist())
                .andExpect(jsonPath("$.resource_id").doesNotExist())
                .andExpect(jsonPath("$.resource_id_present").value(true))
                .andExpect(jsonPath("$.resource_id_hash").exists())
                .andExpect(jsonPath("$.lease_owner").doesNotExist())
                .andExpect(jsonPath("$.lease_owner_present").value(true))
                .andExpect(jsonPath("$.lease_owner_hash").exists())
                .andExpect(jsonPath("$.last_error").doesNotExist())
                .andExpect(jsonPath("$.last_error_code").value("UNSAFE_ERROR_REDACTED"))
                .andExpect(content().string(not(containsString("alert-sensitive"))))
                .andExpect(content().string(not(containsString("owner-sensitive-after-restart"))))
                .andExpect(content().string(not(containsString("token=secret"))))
                .andExpect(content().string(not(containsString("/api/v1"))));
    }

    private RegulatedMutationRecoveryService service(String idempotencyKey, RegulatedMutationCommandInspectionResponse response) {
        RegulatedMutationRecoveryService service = mock(RegulatedMutationRecoveryService.class);
        when(service.inspect(idempotencyKey)).thenReturn(response);
        return service;
    }

    private RegulatedMutationCommandInspectionResponse response(
            String state,
            String executionStatus,
            boolean responseSnapshotPresent,
            String degradationReason,
            String leaseOwner
    ) {
        return responseWithLastErrorCode(state, executionStatus, responseSnapshotPresent, degradationReason, leaseOwner,
                safeError(degradationReason));
    }

    private RegulatedMutationCommandInspectionResponse responseWithLastErrorCode(
            String state,
            String executionStatus,
            boolean responseSnapshotPresent,
            String degradationReason,
            String leaseOwner,
            String lastErrorCode
    ) {
        return new RegulatedMutationCommandInspectionResponse(
                RegulatedMutationIntentHasher.hash("idem"),
                "idem-a...tart",
                "SUBMIT_ANALYST_DECISION",
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION.name(),
                "ALERT",
                true,
                RegulatedMutationIntentHasher.hash("resourceId=alert-sensitive"),
                state,
                executionStatus,
                leaseOwner != null,
                leaseOwner == null ? null : RegulatedMutationIntentHasher.hash("leaseOwner=" + leaseOwner),
                Instant.parse("2026-05-06T08:00:00Z"),
                1,
                responseSnapshotPresent,
                state.equals("AUDIT_ATTEMPTED") ? "attempted-audit" : null,
                null,
                null,
                degradationReason,
                lastErrorCode,
                Instant.parse("2026-05-06T08:01:00Z")
        );
    }

    private String safeError(String value) {
        if (value == null || value.matches("^[A-Z0-9_]{1,80}$")) {
            return value;
        }
        return "UNSAFE_ERROR_REDACTED";
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder inspect(String idempotencyKey) {
        return get("/api/v1/regulated-mutations/" + idempotencyKey)
                .principal(new TestingAuthenticationToken("ops-admin", "n/a", "FRAUD_OPS_ADMIN"));
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
