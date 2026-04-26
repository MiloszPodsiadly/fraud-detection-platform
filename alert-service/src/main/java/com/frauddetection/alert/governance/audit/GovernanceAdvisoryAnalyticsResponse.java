package com.frauddetection.alert.governance.audit;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

public record GovernanceAdvisoryAnalyticsResponse(
        @JsonProperty("status")
        String status,

        @JsonProperty("window")
        Window window,

        @JsonProperty("totals")
        Totals totals,

        @JsonProperty("decision_distribution")
        Map<GovernanceAuditDecision, Integer> decisionDistribution,

        @JsonProperty("lifecycle_distribution")
        Map<GovernanceAdvisoryLifecycleStatus, Integer> lifecycleDistribution,

        @JsonProperty("review_timeliness")
        ReviewTimeliness reviewTimeliness
) {
    public static GovernanceAdvisoryAnalyticsResponse empty(String status, Instant from, Instant to, int days) {
        return new GovernanceAdvisoryAnalyticsResponse(
                status,
                new Window(from, to, days),
                new Totals(0, 0, 0),
                emptyDecisionDistribution(),
                emptyLifecycleDistribution(),
                new ReviewTimeliness("LOW_CONFIDENCE", 0.0, 0.0)
        );
    }

    public static Map<GovernanceAuditDecision, Integer> emptyDecisionDistribution() {
        EnumMap<GovernanceAuditDecision, Integer> distribution = new EnumMap<>(GovernanceAuditDecision.class);
        for (GovernanceAuditDecision decision : GovernanceAuditDecision.values()) {
            distribution.put(decision, 0);
        }
        return distribution;
    }

    public static Map<GovernanceAdvisoryLifecycleStatus, Integer> emptyLifecycleDistribution() {
        EnumMap<GovernanceAdvisoryLifecycleStatus, Integer> distribution = new EnumMap<>(GovernanceAdvisoryLifecycleStatus.class);
        for (GovernanceAdvisoryLifecycleStatus status : GovernanceAdvisoryLifecycleStatus.values()) {
            distribution.put(status, 0);
        }
        return distribution;
    }

    public record Window(
            @JsonProperty("from")
            Instant from,

            @JsonProperty("to")
            Instant to,

            @JsonProperty("days")
            int days
    ) {
    }

    public record Totals(
            @JsonProperty("advisories")
            int advisories,

            @JsonProperty("reviewed")
            int reviewed,

            @JsonProperty("open")
            int open
    ) {
    }

    public record ReviewTimeliness(
            @JsonProperty("status")
            String status,

            @JsonProperty("time_to_first_review_p50_minutes")
            double timeToFirstReviewP50Minutes,

            @JsonProperty("time_to_first_review_p95_minutes")
            double timeToFirstReviewP95Minutes
    ) {
    }
}
