package com.frauddetection.enricher.graph;

import java.util.List;
import java.util.Optional;
import java.time.Duration;
import java.time.Instant;

/** Private read port. G03-B adds only admitted-handle writes, not caller-created edge writes. */
interface GraphProjectionStore {
    GraphAppendOutcome appendVerified(GraphEvidenceAdmission.AdmittedRelation admitted);
    GraphAppendOutcome markRevokedOrFence(GraphEvidenceAdmission.AdmittedRelation admitted);
    Optional<GraphStoredEdge> findLogicalRelation(GraphEdgeFact candidate);
    List<GraphStoredEdge> findNeighbors(GraphNodeRef node, int limit);
    List<GraphStoredEdge> findOccurrenceNeighbors(GraphIdentityScope scope, GraphOccurrenceRef occurrence,
                                                   Instant asOf, int limit, Duration maxTime);
}
