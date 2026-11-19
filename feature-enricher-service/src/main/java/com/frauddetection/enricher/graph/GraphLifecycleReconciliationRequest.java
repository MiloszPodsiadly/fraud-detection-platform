package com.frauddetection.enricher.graph;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Exact logical relation key plus a fresh, bounded server-owned reconciliation operation. */
record GraphLifecycleReconciliationRequest(GraphIdentityScope scope, GraphOccurrenceRef occurrence,
                                           String associatedIdentityToken, int pseudonymKeyVersion,
                                           String requestingPrincipal, Instant operationDeadline) {
    static final String PURPOSE = "GRAPH_LIFECYCLE_RECONCILIATION";
    private static final Pattern PRINCIPAL = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    GraphLifecycleReconciliationRequest {
        Objects.requireNonNull(scope, "GRAPH_SCOPE_REQUIRED");
        Objects.requireNonNull(occurrence, "GRAPH_OCCURRENCE_REQUIRED");
        if (!scope.equals(occurrence.scope())) { throw new IllegalArgumentException("GRAPH_QUERY_SCOPE_INVALID"); }
        GraphValidation.token(associatedIdentityToken);
        GraphValidation.version(pseudonymKeyVersion);
        if (requestingPrincipal == null || !PRINCIPAL.matcher(requestingPrincipal).matches()) {
            throw new IllegalArgumentException("GRAPH_REQUEST_PRINCIPAL_INVALID");
        }
        GraphValidation.millisecondInstant(operationDeadline);
    }

    @Override public String toString() { return "GraphLifecycleReconciliationRequest[redacted]"; }
}
