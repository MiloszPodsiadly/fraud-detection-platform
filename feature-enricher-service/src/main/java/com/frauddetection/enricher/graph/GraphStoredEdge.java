package com.frauddetection.enricher.graph;

import java.time.Instant;
import java.util.Objects;

/** Stored candidate plus mutable lifecycle metadata; owner authority is still resolved on every use. */
record GraphStoredEdge(GraphEdgeFact edge, GraphEdgeLifecycle lifecycle, Instant admittedAt, Instant validUntil) {
    GraphStoredEdge {
        Objects.requireNonNull(edge, "GRAPH_EDGE_REQUIRED");
        Objects.requireNonNull(lifecycle, "GRAPH_LIFECYCLE_REQUIRED");
        GraphValidation.millisecondInstant(admittedAt);
        GraphValidation.millisecondInstant(validUntil);
        if (!validUntil.isAfter(edge.evidence().asOf()) || validUntil.isAfter(edge.evidence().expiresAt())) {
            throw new IllegalArgumentException("GRAPH_STORED_EDGE_EXPIRY_INVALID");
        }
    }

    @Override public String toString() { return "GraphStoredEdge[" + lifecycle + ",redacted]"; }
}
