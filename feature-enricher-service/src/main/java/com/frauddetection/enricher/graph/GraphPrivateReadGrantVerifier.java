package com.frauddetection.enricher.graph;

import java.time.Instant;

/** Runtime-current Graph grant verifier, independent of source and domain owner assertions. */
interface GraphPrivateReadGrantVerifier {
    GraphPolicyDecision verify(GraphPrivateReadGrant grant, String requestingPrincipal,
                               GraphIdentityScope exactScope, Instant now);
}
