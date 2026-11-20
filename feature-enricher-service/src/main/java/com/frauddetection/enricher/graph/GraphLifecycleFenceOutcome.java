package com.frauddetection.enricher.graph;

import java.util.Objects;

record GraphLifecycleFenceOutcome(State state, GraphReadReason reason,
                                  GraphEvidenceAdmission.LifecycleFence fence) {
    enum State { FENCE, NO_CHANGE, PENDING, UNAVAILABLE, DENIED }

    GraphLifecycleFenceOutcome {
        Objects.requireNonNull(state, "GRAPH_FENCE_STATE_REQUIRED");
        Objects.requireNonNull(reason, "GRAPH_FENCE_REASON_REQUIRED");
        if (state == State.FENCE
                ? fence == null || (fence.lifecycle() == GraphEdgeLifecycle.REVOKED
                    ? reason != GraphReadReason.AUTHORITY_REVOKED
                    : reason != GraphReadReason.CONFLICTING_EVIDENCE)
                : fence != null || (state == State.NO_CHANGE
                    ? reason != GraphReadReason.NONE : reason == GraphReadReason.NONE)) {
            throw new IllegalArgumentException("GRAPH_FENCE_OUTCOME_INVALID");
        }
    }

    static GraphLifecycleFenceOutcome fence(GraphEvidenceAdmission.LifecycleFence fence) {
        return new GraphLifecycleFenceOutcome(State.FENCE,
                fence.lifecycle() == GraphEdgeLifecycle.CONFLICT
                        ? GraphReadReason.CONFLICTING_EVIDENCE : GraphReadReason.AUTHORITY_REVOKED, fence);
    }

    static GraphLifecycleFenceOutcome noChange() {
        return new GraphLifecycleFenceOutcome(State.NO_CHANGE, GraphReadReason.NONE, null);
    }

    static GraphLifecycleFenceOutcome pending(GraphReadReason reason) {
        return new GraphLifecycleFenceOutcome(State.PENDING, reason, null);
    }

    static GraphLifecycleFenceOutcome unavailable(GraphReadReason reason) {
        return new GraphLifecycleFenceOutcome(State.UNAVAILABLE, reason, null);
    }

    static GraphLifecycleFenceOutcome denied(GraphReadReason reason) {
        return new GraphLifecycleFenceOutcome(State.DENIED, reason, null);
    }
}
