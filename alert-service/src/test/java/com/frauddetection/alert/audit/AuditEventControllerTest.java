package com.frauddetection.alert.audit;

import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = AuditEventController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        }
)
@AutoConfigureMockMvc(addFilters = false)
@Import(AlertServiceExceptionHandler.class)
class AuditEventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuditEventReadService auditEventReadService;

    @MockBean
    private SensitiveReadAuditService sensitiveReadAuditService;

    @Test
    void shouldReturnStableAuditReadContract() throws Exception {
        when(auditEventReadService.readEvents(
                eq("SUBMIT_ANALYST_DECISION"),
                eq("admin-1"),
                eq("ALERT"),
                eq("alert-1"),
                eq("2026-04-26T00:00:00Z"),
                eq("2026-04-26T10:00:00Z"),
                eq(50)
        )).thenReturn(new AuditEventReadResponse(
                "AVAILABLE",
                null,
                null,
                1,
                50,
                List.of(new AuditEventResponse(
                        "audit-1",
                        "SUBMIT_ANALYST_DECISION",
                        "admin-1",
                        "admin-1",
                        List.of("FRAUD_OPS_ADMIN"),
                        "HUMAN",
                        "ALERT",
                        "alert-1",
                        "SUBMIT_ANALYST_DECISION",
                        "SUCCESS",
                        Instant.parse("2026-04-26T09:00:00Z"),
                        "corr-1",
                        "alert-service",
                        "source_service:alert-service",
                        1L,
                        null,
                        new AuditEventMetadataSummary("corr-1", null),
                        null,
                        "hash-1",
                        "SHA-256",
                        "1.0",
                        false,
                        null,
                        true,
                        BusinessEffectiveStatus.TRUE,
                        AuditEvidenceStatus.LOCAL_ONLY,
                        AuditExternalAnchorStatus.LOCAL_STATUS_UNVERIFIED,
                        CompensationType.UNKNOWN,
                        null
                ))
        ));

        mockMvc.perform(get("/api/v1/audit/events")
                        .param("event_type", "SUBMIT_ANALYST_DECISION")
                        .param("actor_id", "admin-1")
                        .param("resource_type", "ALERT")
                        .param("resource_id", "alert-1")
                        .param("from", "2026-04-26T00:00:00Z")
                        .param("to", "2026-04-26T10:00:00Z")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.reason_code").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.events[0].audit_event_id").value("audit-1"))
                .andExpect(jsonPath("$.events[0].event_type").value("SUBMIT_ANALYST_DECISION"))
                .andExpect(jsonPath("$.events[0].actor_id").value("admin-1"))
                .andExpect(jsonPath("$.events[0].actor_display_name").value("admin-1"))
                .andExpect(jsonPath("$.events[0].actor_roles[0]").value("FRAUD_OPS_ADMIN"))
                .andExpect(jsonPath("$.events[0].actor_type").value("HUMAN"))
                .andExpect(jsonPath("$.events[0].resource_type").value("ALERT"))
                .andExpect(jsonPath("$.events[0].resource_id").value("alert-1"))
                .andExpect(jsonPath("$.events[0].action").value("SUBMIT_ANALYST_DECISION"))
                .andExpect(jsonPath("$.events[0].outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.events[0].occurred_at").value("2026-04-26T09:00:00Z"))
                .andExpect(jsonPath("$.events[0].metadata_summary.correlation_id").value("corr-1"))
                .andExpect(jsonPath("$.events[0].event_hash").value("hash-1"))
                .andExpect(jsonPath("$.events[0].hash_algorithm").value("SHA-256"))
                .andExpect(jsonPath("$.events[0].business_effective").value(true))
                .andExpect(jsonPath("$.events[0].business_effective_status").value("TRUE"))
                .andExpect(jsonPath("$.events[0].audit_evidence_status").value("LOCAL_ONLY"))
                .andExpect(jsonPath("$.events[0].external_anchor_status").value("LOCAL_STATUS_UNVERIFIED"))
                .andExpect(jsonPath("$.events[0].compensation_type").value("UNKNOWN"))
                .andExpect(jsonPath("$.events[0].actor_authorities").doesNotExist())
                .andExpect(jsonPath("$.events[0].request_body").doesNotExist())
                .andExpect(jsonPath("$.events[0].response_body").doesNotExist());
    }

    @Test
    void shouldReturnBadRequestForInvalidQuery() throws Exception {
        when(auditEventReadService.readEvents(eq("APPROVE_MODEL"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenThrow(new InvalidAuditEventQueryException(List.of("event_type: unsupported value")));

        mockMvc.perform(get("/api/v1/audit/events").param("event_type", "APPROVE_MODEL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid audit event query."))
                .andExpect(jsonPath("$.details[0]").value("event_type: unsupported value"));
    }

    @Test
    void shouldReturnStableUnavailableAuditReadContract() throws Exception {
        when(auditEventReadService.readEvents(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(50)))
                .thenReturn(AuditEventReadResponse.unavailable(50));

        mockMvc.perform(get("/api/v1/audit/events").param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.reason_code").value("AUDIT_STORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Audit event store is currently unavailable."))
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.events").isEmpty())
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }
}
