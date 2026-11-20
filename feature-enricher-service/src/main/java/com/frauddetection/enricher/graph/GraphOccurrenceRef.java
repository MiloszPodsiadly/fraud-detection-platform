package com.frauddetection.enricher.graph;

import java.time.Instant;
import java.util.Objects;

/** Exact owner-derived occurrence token and event time, without source event IDs or payload data. */
public record GraphOccurrenceRef(GraphIdentityScope scope, String occurrenceToken, Instant eventTime) {
    public GraphOccurrenceRef {
        Objects.requireNonNull(scope, "GRAPH_SCOPE_REQUIRED");
        GraphValidation.token(occurrenceToken);
        GraphValidation.millisecondInstant(eventTime);
    }

    @Override public String toString() { return "GraphOccurrenceRef[redacted]"; }
}
