package com.frauddetection.alert.governance.audit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GovernanceAdvisoryEvent(
        @JsonProperty("event_id")
        String eventId,

        @JsonProperty("event_type")
        String eventType,

        @JsonProperty("severity")
        String severity,

        @JsonProperty("drift_status")
        String driftStatus,

        @JsonProperty("confidence")
        String confidence,

        @JsonProperty("advisory_confidence_context")
        String advisoryConfidenceContext,

        @JsonProperty("model_name")
        String modelName,

        @JsonProperty("model_version")
        String modelVersion,

        @JsonProperty("lifecycle_context")
        Map<String, Object> lifecycleContext,

        @JsonProperty("recommended_actions")
        List<String> recommendedActions,

        @JsonProperty("explanation")
        String explanation,

        @JsonProperty("created_at")
        String createdAt,

        @JsonProperty("lifecycle_status")
        GovernanceAdvisoryLifecycleStatus lifecycleStatus
) {
    public GovernanceAdvisoryEvent withLifecycleStatus(GovernanceAdvisoryLifecycleStatus nextLifecycleStatus) {
        return new GovernanceAdvisoryEvent(
                eventId,
                eventType,
                severity,
                driftStatus,
                confidence,
                advisoryConfidenceContext,
                modelName,
                modelVersion,
                lifecycleContext,
                recommendedActions,
                explanation,
                createdAt,
                nextLifecycleStatus
        );
    }
}
