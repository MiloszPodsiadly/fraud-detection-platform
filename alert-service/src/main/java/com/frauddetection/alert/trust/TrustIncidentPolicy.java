package com.frauddetection.alert.trust;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class TrustIncidentPolicy {

    private static final Set<TrustIncidentStatus> OPEN_STATUSES = Set.of(
            TrustIncidentStatus.OPEN,
            TrustIncidentStatus.ACKNOWLEDGED
    );

    public Set<TrustIncidentStatus> openStatuses() {
        return OPEN_STATUSES;
    }

    public TrustIncidentSeverity severity(String type) {
        return switch (type) {
            case "OUTBOX_TERMINAL_FAILURE",
                 "REGULATED_MUTATION_RECOVERY_REQUIRED",
                 "EXTERNAL_ANCHOR_GAP",
                 "TRUST_AUTHORITY_UNAVAILABLE" -> TrustIncidentSeverity.CRITICAL;
            case "OUTBOX_PUBLISH_CONFIRMATION_UNKNOWN",
                 "OUTBOX_PROJECTION_MISMATCH",
                 "REGULATED_MUTATION_COMMITTED_DEGRADED",
                 "EVIDENCE_CONFIRMATION_FAILED",
                 "AUDIT_DEGRADATION_UNRESOLVED",
                 "COVERAGE_UNAVAILABLE" -> TrustIncidentSeverity.HIGH;
            default -> TrustIncidentSeverity.MEDIUM;
        };
    }

    public String healthStatus(TrustIncidentSummary summary) {
        if (summary.openCriticalIncidentCount() > 0 || summary.unacknowledgedCriticalIncidentCount() > 0) {
            return "CRITICAL";
        }
        if (summary.openHighIncidentCount() > 0) {
            return "DEGRADED";
        }
        return "HEALTHY";
    }

    public List<String> boundedEvidenceRefs(List<String> refs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        return refs.stream()
                .filter(ref -> ref != null && !ref.isBlank())
                .map(String::trim)
                .limit(10)
                .toList();
    }
}
