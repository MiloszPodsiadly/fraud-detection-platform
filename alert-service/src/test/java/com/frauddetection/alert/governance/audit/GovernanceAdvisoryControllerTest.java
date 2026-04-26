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
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = GovernanceAdvisoryController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        }
)
@AutoConfigureMockMvc(addFilters = false)
@Import(AlertServiceExceptionHandler.class)
class GovernanceAdvisoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GovernanceAdvisoryProjectionService projectionService;

    @Test
    void shouldReturnLifecycleStatusOnAdvisoryList() throws Exception {
        when(projectionService.listAdvisories(
                eq(new GovernanceAdvisoryQuery("HIGH", "2026-04-21.trained.v1", 25)),
                eq(GovernanceAdvisoryLifecycleStatus.ACKNOWLEDGED)
        )).thenReturn(new GovernanceAdvisoryListResponse("AVAILABLE", 1, 200, List.of(advisoryEvent())));

        mockMvc.perform(get("/governance/advisories")
                        .queryParam("severity", "HIGH")
                        .queryParam("model_version", "2026-04-21.trained.v1")
                        .queryParam("lifecycle_status", "ACKNOWLEDGED")
                        .queryParam("limit", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advisory_events[0].event_id").value("advisory-1"))
                .andExpect(jsonPath("$.advisory_events[0].lifecycle_status").value("ACKNOWLEDGED"));
    }

    @Test
    void shouldReturnLifecycleStatusOnSingleAdvisory() throws Exception {
        when(projectionService.getAdvisory("advisory-1")).thenReturn(advisoryEvent());

        mockMvc.perform(get("/governance/advisories/advisory-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event_id").value("advisory-1"))
                .andExpect(jsonPath("$.lifecycle_status").value("ACKNOWLEDGED"));
    }

    @Test
    void shouldRejectInvalidLifecycleFilter() throws Exception {
        mockMvc.perform(get("/governance/advisories")
                        .queryParam("lifecycle_status", "IN_PROGRESS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed."));
    }

    private GovernanceAdvisoryEvent advisoryEvent() {
        return new GovernanceAdvisoryEvent(
                "advisory-1",
                "GOVERNANCE_DRIFT_ADVISORY",
                "HIGH",
                "DRIFT",
                "HIGH",
                "SUFFICIENT_DATA",
                "python-logistic-fraud-model",
                "2026-04-21.trained.v1",
                Map.of("current_model_version", "2026-04-21.trained.v1"),
                List.of("KEEP_SCORING_UNCHANGED"),
                "score p95 increased compared to reference profile",
                "2026-04-26T00:02:00+00:00",
                GovernanceAdvisoryLifecycleStatus.ACKNOWLEDGED
        );
    }
}
