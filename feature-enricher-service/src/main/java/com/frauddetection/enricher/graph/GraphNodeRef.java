package com.frauddetection.enricher.graph;

import java.util.Objects;

/** A scoped graph node; its canonical ID is always derived from all identity fields. */
public record GraphNodeRef(GraphNodeKind kind, GraphIdentityScope scope, String identityToken,
                           int pseudonymKeyVersion) {
    public GraphNodeRef {
        Objects.requireNonNull(kind, "GRAPH_NODE_KIND_REQUIRED");
        Objects.requireNonNull(scope, "GRAPH_SCOPE_REQUIRED");
        GraphValidation.token(identityToken);
        if (kind == GraphNodeKind.TRANSACTION_OCCURRENCE) {
            if (pseudonymKeyVersion != 0) {
                throw new IllegalArgumentException("GRAPH_OCCURRENCE_KEY_VERSION_INVALID");
            }
        } else {
            GraphValidation.version(pseudonymKeyVersion);
        }
    }

    public static GraphNodeRef occurrence(GraphOccurrenceRef occurrence) {
        Objects.requireNonNull(occurrence, "GRAPH_OCCURRENCE_REQUIRED");
        return new GraphNodeRef(GraphNodeKind.TRANSACTION_OCCURRENCE, occurrence.scope(),
                occurrence.occurrenceToken(), 0);
    }

    public static GraphNodeRef device(GraphIdentityScope scope, String devicePseudonym, int keyVersion) {
        return new GraphNodeRef(GraphNodeKind.DEVICE_PSEUDONYM, scope, devicePseudonym, keyVersion);
    }

    public String canonicalId() {
        var fields = new java.util.ArrayList<String>();
        fields.add(kind.name());
        fields.addAll(scope.canonicalFields());
        fields.add(Integer.toString(pseudonymKeyVersion));
        fields.add(identityToken);
        return GraphCanonical.digest("node/v1", fields.toArray(String[]::new));
    }

    @Override public String toString() { return "GraphNodeRef[redacted]"; }
}
