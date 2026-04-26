package com.frauddetection.alert.governance.audit;

import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = GovernanceAuditController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        }
)
@AutoConfigureMockMvc(addFilters = false)
@Import(AlertServiceExceptionHandler.class)
class GovernanceAuditControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GovernanceAuditService governanceAuditService;

    @Test
    void shouldAppendGovernanceAuditEvent() throws Exception {
        when(governanceAuditService.appendAudit(eq("advisory-1"), any()))
                .thenReturn(sampleAudit());

        mockMvc.perform(post("/governance/advisories/advisory-1/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "ACKNOWLEDGED",
                                  "note": "Reviewed by operator"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.audit_id").value("audit-1"))
                .andExpect(jsonPath("$.actor_id").value("analyst-1"))
                .andExpect(jsonPath("$.model_version").value("2026-04-21.trained.v1"));
    }

    @Test
    void shouldReturnBoundedHistoryContract() throws Exception {
        when(governanceAuditService.history("advisory-1"))
                .thenReturn(new GovernanceAuditHistoryResponse("advisory-1", "AVAILABLE", List.of(sampleAudit())));

        mockMvc.perform(get("/governance/advisories/advisory-1/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advisory_event_id").value("advisory-1"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.audit_events[0].decision").value("ACKNOWLEDGED"));
    }

    @Test
    void shouldRejectOversizedNote() throws Exception {
        mockMvc.perform(post("/governance/advisories/advisory-1/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACKNOWLEDGED\",\"note\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed."));
    }

    @Test
    void shouldRejectFrontendProvidedActorFields() throws Exception {
        mockMvc.perform(post("/governance/advisories/advisory-1/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "ACKNOWLEDGED",
                                  "actor_id": "spoofed-actor"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed JSON request."));
    }

    @Test
    void shouldRejectInvalidDecision() throws Exception {
        when(governanceAuditService.appendAudit(eq("advisory-1"), any()))
                .thenThrow(new InvalidGovernanceAuditDecisionException());

        mockMvc.perform(post("/governance/advisories/advisory-1/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE_MODEL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid governance audit decision."));
    }

    private GovernanceAuditEventResponse sampleAudit() {
        return new GovernanceAuditEventResponse(
                "audit-1",
                "advisory-1",
                GovernanceAuditDecision.ACKNOWLEDGED,
                "Reviewed by operator",
                "analyst-1",
                "analyst-1",
                List.of("ANALYST"),
                Instant.parse("2026-04-26T00:00:00Z"),
                "python-logistic-fraud-model",
                "2026-04-21.trained.v1",
                "HIGH",
                "HIGH",
                "SUFFICIENT_DATA"
        );
    }
}
