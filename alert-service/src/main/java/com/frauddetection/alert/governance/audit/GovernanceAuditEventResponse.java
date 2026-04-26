package com.frauddetection.alert.governance.audit;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record GovernanceAuditEventResponse(
        @JsonProperty("audit_id")
        String auditId,

        @JsonProperty("advisory_event_id")
        String advisoryEventId,

        @JsonProperty("decision")
        GovernanceAuditDecision decision,

        @JsonProperty("note")
        String note,

        @JsonProperty("actor_id")
        String actorId,

        @JsonProperty("actor_display_name")
        String actorDisplayName,

        @JsonProperty("actor_roles")
        List<String> actorRoles,

        @JsonProperty("created_at")
        Instant createdAt,

        @JsonProperty("model_name")
        String modelName,

        @JsonProperty("model_version")
        String modelVersion,

        @JsonProperty("advisory_severity")
        String advisorySeverity,

        @JsonProperty("advisory_confidence")
        String advisoryConfidence,

        @JsonProperty("advisory_confidence_context")
        String advisoryConfidenceContext
) {
    public static GovernanceAuditEventResponse from(GovernanceAuditEventDocument document) {
        return new GovernanceAuditEventResponse(
                document.getAuditId(),
                document.getAdvisoryEventId(),
                document.getDecision(),
                document.getNote(),
                document.getActorId(),
                document.getActorDisplayName(),
                document.getActorRoles(),
                document.getCreatedAt(),
                document.getModelName(),
                document.getModelVersion(),
                document.getAdvisorySeverity(),
                document.getAdvisoryConfidence(),
                document.getAdvisoryConfidenceContext()
        );
    }
}
