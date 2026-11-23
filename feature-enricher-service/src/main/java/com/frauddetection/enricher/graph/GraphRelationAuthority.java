package com.frauddetection.enricher.graph;

import java.time.Duration;

/** Graph admission can only request an owner resolution; it cannot mint or supply domain evidence. */
interface GraphRelationAuthority {
    GraphRelationResolution resolveForAdmission(String ownerReference);
    GraphRelationResolution revalidate(GraphEvidenceRef expected, String requestingPrincipal);

    /** Allows the bounded two-hop reader to pass its remaining operation budget to owner adapters. */
    default GraphRelationResolution revalidate(GraphEvidenceRef expected, String requestingPrincipal,
                                               Duration remainingBudget) {
        if (remainingBudget == null || remainingBudget.isZero() || remainingBudget.isNegative()
                || remainingBudget.compareTo(GraphQueryLimits.DEFAULT.maxQueryTime()) > 0) {
            return GraphRelationResolution.unavailable(GraphReadReason.DEADLINE_EXCEEDED);
        }
        return GraphRelationResolution.unavailable(GraphReadReason.NOT_SUPPORTED);
    }
}
