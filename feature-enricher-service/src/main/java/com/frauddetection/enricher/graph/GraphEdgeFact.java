package com.frauddetection.enricher.graph;

import java.util.Objects;

/** Immutable candidate fact. A fact is not verified until current owner authority is resolved. */
public record GraphEdgeFact(GraphProjectionVersion projectionVersion, GraphEdgeType type,
                            GraphNodeRef from, GraphNodeRef to, GraphEvidenceRef evidence) {
    public GraphEdgeFact {
        Objects.requireNonNull(projectionVersion, "GRAPH_PROJECTION_VERSION_REQUIRED");
        Objects.requireNonNull(type, "GRAPH_EDGE_TYPE_REQUIRED");
        Objects.requireNonNull(from, "GRAPH_FROM_NODE_REQUIRED");
        Objects.requireNonNull(to, "GRAPH_TO_NODE_REQUIRED");
        Objects.requireNonNull(evidence, "GRAPH_EVIDENCE_REQUIRED");
        if (type != GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE
                || from.kind() != GraphNodeKind.TRANSACTION_OCCURRENCE
                || to.kind() != GraphNodeKind.DEVICE_PSEUDONYM
                || !from.scope().equals(to.scope())
                || !from.scope().equals(evidence.scope())
                || !from.identityToken().equals(evidence.occurrence().occurrenceToken())
                || !to.identityToken().equals(evidence.associatedIdentityToken())
                || to.pseudonymKeyVersion() != evidence.pseudonymKeyVersion()) {
            throw new IllegalArgumentException("GRAPH_EDGE_SCOPE_OR_TYPE_INVALID");
        }
    }

    /** Deterministic identity includes both scoped nodes and every owner evidence reference. */
    public String canonicalId() {
        return GraphCanonical.digest("edge/v1", Integer.toString(projectionVersion.schemaVersion()),
                Integer.toString(projectionVersion.canonicalKeyVersion()), type.name(),
                from.canonicalId(), to.canonicalId(), evidence.canonicalReferenceId());
    }

    @Override public String toString() { return "GraphEdgeFact[redacted]"; }
}
