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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
    void shouldReturnAnalyticsForBoundedWindow() throws Exception {
        when(projectionService.analytics(7)).thenReturn(analyticsResponse());

        mockMvc.perform(get("/governance/advisories/analytics")
                        .queryParam("window_days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.reason_code").doesNotExist())
                .andExpect(jsonPath("$.window.days").value(7))
                .andExpect(jsonPath("$.totals.advisories").value(2))
                .andExpect(jsonPath("$.decision_distribution.ACKNOWLEDGED").value(1))
                .andExpect(content().json("""
                        {
                          "status": "AVAILABLE",
                          "window": {
                            "from": "2026-04-19T00:00:00Z",
                            "to": "2026-04-26T00:00:00Z",
                            "days": 7
                          },
                          "totals": {
                            "advisories": 2,
                            "reviewed": 1,
                            "open": 1,
                            "resolved": 1,
                            "unknown": 0
                          },
                          "decision_distribution": {
                            "ACKNOWLEDGED": 1,
                            "NEEDS_FOLLOW_UP": 0,
                            "DISMISSED_AS_NOISE": 0
                          },
                          "lifecycle_distribution": {
                            "OPEN": 1,
                            "UNKNOWN": 0,
                            "ACKNOWLEDGED": 1,
                            "NEEDS_FOLLOW_UP": 0,
                            "DISMISSED_AS_NOISE": 0
                          },
                          "review_timeliness": {
                            "status": "AVAILABLE",
                            "time_to_first_review_p50_minutes": 10.0,
                            "time_to_first_review_p95_minutes": 10.0
                          }
                        }
                        """, true));
    }

    @Test
    void shouldRejectInvalidAnalyticsWindow() throws Exception {
        mockMvc.perform(get("/governance/advisories/analytics")
                        .queryParam("window_days", "31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed."));
    }

    @Test
    void shouldRejectInvalidLifecycleFilter() throws Exception {
        mockMvc.perform(get("/governance/advisories")
                        .queryParam("lifecycle_status", "IN_PROGRESS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed."));
    }

    @Test
    void shouldReturnNotFoundWhenAdvisoryIsMissing() throws Exception {
        when(projectionService.getAdvisory("missing-advisory"))
                .thenThrow(new GovernanceAdvisoryNotFoundException("missing-advisory"));

        mockMvc.perform(get("/governance/advisories/missing-advisory"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Governance advisory event not found: missing-advisory"));
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

    private GovernanceAdvisoryAnalyticsResponse analyticsResponse() {
        java.util.Map<GovernanceAuditDecision, Integer> decisionDistribution =
                GovernanceAdvisoryAnalyticsResponse.emptyDecisionDistribution();
        decisionDistribution.put(GovernanceAuditDecision.ACKNOWLEDGED, 1);
        java.util.Map<GovernanceAdvisoryLifecycleStatus, Integer> lifecycleDistribution =
                GovernanceAdvisoryAnalyticsResponse.emptyLifecycleDistribution();
        lifecycleDistribution.put(GovernanceAdvisoryLifecycleStatus.ACKNOWLEDGED, 1);
        lifecycleDistribution.put(GovernanceAdvisoryLifecycleStatus.OPEN, 1);
        return new GovernanceAdvisoryAnalyticsResponse(
                "AVAILABLE",
                null,
                new GovernanceAdvisoryAnalyticsResponse.Window(
                        java.time.Instant.parse("2026-04-19T00:00:00Z"),
                        java.time.Instant.parse("2026-04-26T00:00:00Z"),
                        7
                ),
                new GovernanceAdvisoryAnalyticsResponse.Totals(2, 1, 1),
                decisionDistribution,
                lifecycleDistribution,
                new GovernanceAdvisoryAnalyticsResponse.ReviewTimeliness("AVAILABLE", 10.0, 10.0)
        );
    }
}
