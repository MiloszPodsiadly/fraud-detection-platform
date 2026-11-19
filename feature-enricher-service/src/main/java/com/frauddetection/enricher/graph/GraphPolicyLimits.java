package com.frauddetection.enricher.graph;

import java.time.Duration;
import java.util.Objects;

/** Immutable server-owned ceilings. Request values may narrow these limits but may not replace them. */
public record GraphPolicyLimits(int maxDepth, int maxNodes, int maxEdges,
                                Duration maxWindow, int maxHistoryRows) {
    public static final int HARD_MAX_DEPTH = 2;
    public static final int HARD_MAX_NODES = 128;
    public static final int HARD_MAX_EDGES = 256;
    public static final int HARD_MAX_HISTORY_ROWS = 1_000;
    public static final Duration HARD_MAX_WINDOW = Duration.ofDays(30);

    public static final GraphPolicyLimits DEFAULT =
            new GraphPolicyLimits(HARD_MAX_DEPTH, HARD_MAX_NODES, HARD_MAX_EDGES,
                    HARD_MAX_WINDOW, HARD_MAX_HISTORY_ROWS);

    public GraphPolicyLimits {
        Objects.requireNonNull(maxWindow, "GRAPH_WINDOW_LIMIT_REQUIRED");
        if (maxDepth < 1 || maxDepth > HARD_MAX_DEPTH
                || maxNodes < 1 || maxNodes > HARD_MAX_NODES
                || maxEdges < 1 || maxEdges > HARD_MAX_EDGES
                || maxHistoryRows < 1 || maxHistoryRows > HARD_MAX_HISTORY_ROWS
                || maxWindow.isZero() || maxWindow.isNegative() || maxWindow.compareTo(HARD_MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("GRAPH_POLICY_LIMIT_INVALID");
        }
    }

    @Override public String toString() { return "GraphPolicyLimits[bounded]"; }
}
