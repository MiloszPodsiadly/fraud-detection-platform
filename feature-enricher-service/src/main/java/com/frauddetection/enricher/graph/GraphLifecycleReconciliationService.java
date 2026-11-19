package com.frauddetection.enricher.graph;

import java.time.Instant;
import java.util.Objects;

/** Internal reconciliation seam. It has no Spring, HTTP, Kafka, or runtime registration. */
final class GraphLifecycleReconciliationService {
    static final String SERVER_PRINCIPAL = "graph_lifecycle_reconciler";

    private final GraphProjectionStore store;
    private final GraphIdentityScope authorizedScope;

    GraphLifecycleReconciliationService(GraphProjectionStore store, GraphIdentityScope authorizedScope) {
        this.store = Objects.requireNonNull(store, "GRAPH_STORE_REQUIRED");
        this.authorizedScope = Objects.requireNonNull(authorizedScope, "GRAPH_SCOPE_REQUIRED");
    }

    GraphAppendOutcome reconcile(GraphOccurrenceRef occurrence, String associatedIdentityToken,
                                 int pseudonymKeyVersion, Instant operationDeadline) {
        if (occurrence == null || !authorizedScope.equals(occurrence.scope())) {
            return GraphAppendOutcome.denied(GraphReadReason.SCOPE_MISMATCH);
        }
        GraphLifecycleReconciliationRequest request = new GraphLifecycleReconciliationRequest(
                authorizedScope, occurrence, associatedIdentityToken, pseudonymKeyVersion,
                SERVER_PRINCIPAL, operationDeadline);
        return store.reconcileLifecycle(request);
    }
}
