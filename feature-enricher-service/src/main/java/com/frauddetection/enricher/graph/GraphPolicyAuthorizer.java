package com.frauddetection.enricher.graph;

import java.time.Instant;

/** Server-owned purpose and scope policy, checked before owner reads and again before admission. */
interface GraphPolicyAuthorizer {
    GraphPolicyDecision authorize(String requestingPrincipal, String purpose, GraphIdentityScope resolvedScope,
                                  Instant deadline, Instant now);
}
