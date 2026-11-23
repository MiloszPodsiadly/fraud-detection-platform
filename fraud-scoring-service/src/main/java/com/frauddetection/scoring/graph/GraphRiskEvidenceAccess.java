package com.frauddetection.scoring.graph;

import com.frauddetection.scoring.context.ScoringContext;

import java.util.Objects;

/**
 * Private scorer boundary for a current, owner-authorized Graph observation.
 * No production provider is registered; descriptions alone do not prove authority.
 */
interface GraphRiskEvidenceAccess {
    GraphRiskEvidenceRead read(ScoringContext context);

    /** Re-reads owner authority and returns the current typed result bound to this exact occurrence. */
    default GraphRiskEvidenceRead requireOccurrence(ScoringContext context, GraphRiskEvidenceRead evidence) {
        Objects.requireNonNull(context, "GRAPH_SCORING_CONTEXT_REQUIRED");
        Objects.requireNonNull(evidence, "GRAPH_EVIDENCE_READ_REQUIRED");
        return GraphRiskEvidenceRead.denied();
    }

    /** Safe default in the absence of an explicitly provisioned test-only provider. */
    static GraphRiskEvidenceAccess unavailable() {
        return context -> {
            Objects.requireNonNull(context, "GRAPH_SCORING_CONTEXT_REQUIRED");
            return GraphRiskEvidenceRead.unavailable(GraphRiskEvidenceRead.Reason.PROVIDER_NOT_CONFIGURED);
        };
    }
}
