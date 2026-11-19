package com.frauddetection.enricher.graph;

/** Internal graph projection schema and canonical-key versions. */
public record GraphProjectionVersion(int schemaVersion, int canonicalKeyVersion) {
    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    public static final int SUPPORTED_CANONICAL_KEY_VERSION = 1;
    public static final GraphProjectionVersion CURRENT =
            new GraphProjectionVersion(SUPPORTED_SCHEMA_VERSION, SUPPORTED_CANONICAL_KEY_VERSION);

    public GraphProjectionVersion {
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION
                || canonicalKeyVersion != SUPPORTED_CANONICAL_KEY_VERSION) {
            throw new IllegalArgumentException("GRAPH_PROJECTION_VERSION_UNSUPPORTED");
        }
    }

    @Override public String toString() { return "GraphProjectionVersion[redacted]"; }
}
