package com.frauddetection.enricher.graph;

import java.util.List;
import java.util.Objects;

/** Bounded page of individually verified rows; it does not assert Graph coverage or verified emptiness. */
public final class GraphReadOutcome {
    private final GraphProjectionState state;
    private final GraphReadReason reason;
    private final List<GraphEdgeFact> edges;
    private final String nextCursor;

    private GraphReadOutcome(GraphProjectionState state, GraphReadReason reason, List<GraphEdgeFact> edges,
                             String nextCursor) {
        this.state = Objects.requireNonNull(state, "GRAPH_STATE_REQUIRED");
        this.reason = Objects.requireNonNull(reason, "GRAPH_REASON_REQUIRED");
        this.edges = List.copyOf(Objects.requireNonNull(edges, "GRAPH_EDGES_REQUIRED"));
        if (state == GraphProjectionState.VERIFIED_BOUNDED) {
            if (this.edges.isEmpty() || reason != GraphReadReason.NONE
                    || nextCursor != null && nextCursor.isBlank()) {
                throw new IllegalArgumentException("GRAPH_VERIFIED_RESULT_INVALID");
            }
        } else if (!this.edges.isEmpty() || reason == GraphReadReason.NONE || nextCursor != null) {
            throw new IllegalArgumentException("GRAPH_NON_VERIFIED_RESULT_INVALID");
        }
        this.nextCursor = nextCursor;
    }

    public static GraphReadOutcome pending(GraphReadReason reason) {
        return nonVerified(GraphProjectionState.PENDING, reason);
    }

    public static GraphReadOutcome unavailable(GraphReadReason reason) {
        return nonVerified(GraphProjectionState.UNAVAILABLE, reason);
    }

    public static GraphReadOutcome denied(GraphReadReason reason) {
        return nonVerified(GraphProjectionState.DENIED, reason);
    }

    public static GraphReadOutcome conflict() {
        return nonVerified(GraphProjectionState.CONFLICT, GraphReadReason.CONFLICTING_EVIDENCE);
    }

    private static GraphReadOutcome nonVerified(GraphProjectionState state, GraphReadReason reason) {
        if (reason == null || reason == GraphReadReason.NONE) {
            throw new IllegalArgumentException("GRAPH_REASON_INVALID");
        }
        return new GraphReadOutcome(state, reason, List.of(), null);
    }

    static GraphReadOutcome verifiedBounded(GraphOwnerResolution resolution, List<GraphEdgeFact> edges,
                                            String nextCursor) {
        Objects.requireNonNull(resolution, "GRAPH_OWNER_RESOLUTION_REQUIRED");
        return new GraphReadOutcome(GraphProjectionState.VERIFIED_BOUNDED, GraphReadReason.NONE, edges, nextCursor);
    }

    public GraphProjectionState state() { return state; }
    public GraphReadReason reason() { return reason; }
    public List<GraphEdgeFact> edges() { return edges; }
    public java.util.Optional<String> nextCursor() { return java.util.Optional.ofNullable(nextCursor); }

    @Override public String toString() { return "GraphReadOutcome[" + state + "," + reason + ",redacted]"; }
}

/** Internal handoff marker. It is not authority; the caller must freshly resolve owner state. */
final class GraphOwnerResolution {
    private GraphOwnerResolution() { }

    static GraphOwnerResolution afterOwnerRevalidation() {
        return new GraphOwnerResolution();
    }

    @Override public String toString() { return "GraphOwnerResolution[redacted]"; }
}
