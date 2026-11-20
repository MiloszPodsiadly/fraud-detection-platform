package com.frauddetection.enricher.graph;

import java.util.Objects;

record GraphAppendOutcome(GraphAppendState state, GraphReadReason reason, GraphStoredEdge storedEdge) {
    GraphAppendOutcome {
        Objects.requireNonNull(state, "GRAPH_APPEND_STATE_REQUIRED");
        Objects.requireNonNull(reason, "GRAPH_APPEND_REASON_REQUIRED");
        if (state == GraphAppendState.APPENDED || state == GraphAppendState.EXISTING) {
            if (reason != GraphReadReason.NONE || storedEdge == null || storedEdge.lifecycle() != GraphEdgeLifecycle.ACTIVE) {
                throw new IllegalArgumentException("GRAPH_APPEND_SUCCESS_INVALID");
            }
        } else if (state == GraphAppendState.ACK_RECOVERED) {
            if (storedEdge == null || (reason != GraphReadReason.NONE && reason != GraphReadReason.CONFLICTING_EVIDENCE
                    && reason != GraphReadReason.AUTHORITY_REVOKED)
                    || reason == GraphReadReason.CONFLICTING_EVIDENCE && storedEdge.lifecycle() != GraphEdgeLifecycle.CONFLICT
                    || reason == GraphReadReason.AUTHORITY_REVOKED && storedEdge.lifecycle() != GraphEdgeLifecycle.REVOKED) {
                throw new IllegalArgumentException("GRAPH_APPEND_RECOVERY_INVALID");
            }
        } else if (state == GraphAppendState.CONFLICT) {
            if (reason != GraphReadReason.CONFLICTING_EVIDENCE
                    || storedEdge != null && storedEdge.lifecycle() != GraphEdgeLifecycle.CONFLICT) {
                throw new IllegalArgumentException("GRAPH_APPEND_CONFLICT_INVALID");
            }
        } else if (state == GraphAppendState.FENCED) {
            if ((reason != GraphReadReason.AUTHORITY_REVOKED && reason != GraphReadReason.CONFLICTING_EVIDENCE)
                    || storedEdge == null
                    || reason == GraphReadReason.AUTHORITY_REVOKED && storedEdge.lifecycle() != GraphEdgeLifecycle.REVOKED
                    || reason == GraphReadReason.CONFLICTING_EVIDENCE && storedEdge.lifecycle() != GraphEdgeLifecycle.CONFLICT) {
                throw new IllegalArgumentException("GRAPH_APPEND_FENCE_INVALID");
            }
        } else if (reason == GraphReadReason.NONE || storedEdge != null) {
            throw new IllegalArgumentException("GRAPH_APPEND_FAILURE_INVALID");
        }
    }

    static GraphAppendOutcome appended(GraphStoredEdge edge) {
        return new GraphAppendOutcome(GraphAppendState.APPENDED, GraphReadReason.NONE, edge);
    }

    static GraphAppendOutcome existing(GraphStoredEdge edge) {
        return new GraphAppendOutcome(GraphAppendState.EXISTING, GraphReadReason.NONE, edge);
    }

    static GraphAppendOutcome conflict(GraphStoredEdge edge) {
        return new GraphAppendOutcome(GraphAppendState.CONFLICT, GraphReadReason.CONFLICTING_EVIDENCE, edge);
    }

    static GraphAppendOutcome recovered(GraphStoredEdge edge) {
        return new GraphAppendOutcome(GraphAppendState.ACK_RECOVERED,
                switch (edge.lifecycle()) {
                    case ACTIVE -> GraphReadReason.NONE;
                    case CONFLICT -> GraphReadReason.CONFLICTING_EVIDENCE;
                    case REVOKED -> GraphReadReason.AUTHORITY_REVOKED;
                }, edge);
    }

    static GraphAppendOutcome fenced(GraphStoredEdge edge) {
        return new GraphAppendOutcome(GraphAppendState.FENCED,
                edge.lifecycle() == GraphEdgeLifecycle.CONFLICT ? GraphReadReason.CONFLICTING_EVIDENCE : GraphReadReason.AUTHORITY_REVOKED, edge);
    }

    static GraphAppendOutcome denied(GraphReadReason reason) {
        return new GraphAppendOutcome(GraphAppendState.DENIED, reason, null);
    }

    static GraphAppendOutcome pending(GraphReadReason reason) {
        return new GraphAppendOutcome(GraphAppendState.PENDING, reason, null);
    }

    static GraphAppendOutcome unavailable(GraphReadReason reason) {
        return new GraphAppendOutcome(GraphAppendState.UNAVAILABLE, reason, null);
    }

    @Override public String toString() { return "GraphAppendOutcome[" + state + "," + reason + ",redacted]"; }
}
