package com.frauddetection.enricher.graph;

import java.time.Instant;

/** Opaque exact scored-fact reference. Only the immutable initial run is admitted. */
public record GraphScoredOccurrenceRef(String scoredEventToken, Instant createdAt,
                                       String fingerprint, String runId) {
    public static final String INITIAL_RUN = "initial";

    public GraphScoredOccurrenceRef {
        GraphValidation.token(scoredEventToken);
        GraphValidation.exactInstant(createdAt);
        GraphValidation.token(fingerprint);
        if (!INITIAL_RUN.equals(runId)) {
            throw new IllegalArgumentException("GRAPH_SCORE_RUN_UNSUPPORTED");
        }
    }

    @Override public String toString() { return "GraphScoredOccurrenceRef[redacted]"; }
}
