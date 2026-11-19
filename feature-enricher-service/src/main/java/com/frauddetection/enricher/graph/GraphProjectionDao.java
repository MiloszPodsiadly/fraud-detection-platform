package com.frauddetection.enricher.graph;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Package-private persistence seam. Runtime callers must read through GraphProjectionReader. */
interface GraphProjectionDao {
    Optional<GraphStoredEdge> findLogicalRelation(GraphEdgeFact candidate);
    List<GraphStoredEdge> findNeighbors(GraphNodeRef node, int limit);
    List<GraphStoredEdge> findOccurrenceNeighbors(GraphIdentityScope scope, GraphOccurrenceRef occurrence,
                                                   Instant asOf, int limit, Duration maxTime);
}
