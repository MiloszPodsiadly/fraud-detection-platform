package com.frauddetection.enricher.graph;

import java.time.Duration;
import java.util.Objects;

/** Immutable server-owned limits for the private Graph reader. */
public record GraphQueryLimits(int maxDepth, int maxNodes, int maxEdges,
                               Duration maxWindow, int maxHistoryRows,
                               int maxPageSize, Duration maxQueryTime) {
    public static final GraphQueryLimits DEFAULT = new GraphQueryLimits(
            2, 128, 256, Duration.ofDays(30), 1_000, 100, Duration.ofSeconds(5));

    public GraphQueryLimits {
        Objects.requireNonNull(maxWindow, "GRAPH_WINDOW_LIMIT_REQUIRED");
        Objects.requireNonNull(maxQueryTime, "GRAPH_QUERY_TIME_LIMIT_REQUIRED");
        if (maxDepth < 1 || maxDepth > 2 || maxNodes < 1 || maxNodes > 128
                || maxEdges < 1 || maxEdges > 256 || maxHistoryRows < 1 || maxHistoryRows > 1_000
                || maxPageSize < 1 || maxPageSize > 100
                || maxWindow.isZero() || maxWindow.isNegative() || maxWindow.compareTo(Duration.ofDays(30)) > 0
                || maxQueryTime.isZero() || maxQueryTime.isNegative() || maxQueryTime.toMillis() < 1
                || maxQueryTime.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("GRAPH_QUERY_LIMIT_INVALID");
        }
    }

    @Override public String toString() { return "GraphQueryLimits[bounded]"; }
}
