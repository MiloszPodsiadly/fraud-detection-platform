package com.frauddetection.enricher.graph;

import java.util.Objects;

record GraphAdmissionOutcome(GraphAdmissionState state, GraphReadReason reason,
                             GraphEvidenceAdmission.AdmittedRelation relation) {
    GraphAdmissionOutcome {
        Objects.requireNonNull(state, "GRAPH_ADMISSION_STATE_REQUIRED");
        Objects.requireNonNull(reason, "GRAPH_ADMISSION_REASON_REQUIRED");
        if (state == GraphAdmissionState.ADMITTED
                ? relation == null || reason != GraphReadReason.NONE
                : relation != null || reason == GraphReadReason.NONE) {
            throw new IllegalArgumentException("GRAPH_ADMISSION_OUTCOME_INVALID");
        }
    }

    static GraphAdmissionOutcome admitted(GraphEvidenceAdmission.AdmittedRelation relation) {
        return new GraphAdmissionOutcome(GraphAdmissionState.ADMITTED, GraphReadReason.NONE, relation);
    }

    static GraphAdmissionOutcome pending(GraphReadReason reason) {
        return new GraphAdmissionOutcome(GraphAdmissionState.PENDING, reason, null);
    }

    static GraphAdmissionOutcome unavailable(GraphReadReason reason) {
        return new GraphAdmissionOutcome(GraphAdmissionState.UNAVAILABLE, reason, null);
    }

    static GraphAdmissionOutcome denied(GraphReadReason reason) {
        return new GraphAdmissionOutcome(GraphAdmissionState.DENIED, reason, null);
    }

    static GraphAdmissionOutcome conflict() {
        return new GraphAdmissionOutcome(GraphAdmissionState.CONFLICT, GraphReadReason.CONFLICTING_EVIDENCE, null);
    }

    @Override public String toString() { return "GraphAdmissionOutcome[" + state + "," + reason + ",redacted]"; }
}
