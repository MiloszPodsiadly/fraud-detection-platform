package com.frauddetection.enricher.graph;

import java.time.Instant;
import java.util.regex.Pattern;

/** Bounded request handle. It carries no caller-asserted scope, identity, score, or evidence. */
record GraphAdmissionRequest(String ownerReference, String requestingPrincipal, Instant deadline) {
    private static final Pattern PRINCIPAL = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    GraphAdmissionRequest {
        GraphValidation.token(ownerReference);
        if (requestingPrincipal == null || !PRINCIPAL.matcher(requestingPrincipal).matches()) {
            throw new IllegalArgumentException("GRAPH_REQUEST_PRINCIPAL_INVALID");
        }
        GraphValidation.millisecondInstant(deadline);
    }

    @Override public String toString() { return "GraphAdmissionRequest[redacted]"; }
}
