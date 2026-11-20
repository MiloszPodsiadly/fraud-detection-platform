package com.frauddetection.enricher.graph;

import java.util.List;

/**
 * Owner-derived opaque source scope. Raw issuer, institution, source, and epoch strings never enter
 * the graph domain; each token is a stable, purpose-specific owner reference.
 */
public record GraphIdentityScope(String sourceIssuerToken, String institutionToken, String sourceToken,
                                 String sourceEpochToken, int identityVersion) {
    public GraphIdentityScope {
        GraphValidation.token(sourceIssuerToken);
        GraphValidation.token(institutionToken);
        GraphValidation.token(sourceToken);
        GraphValidation.token(sourceEpochToken);
        GraphValidation.version(identityVersion);
    }

    List<String> canonicalFields() {
        return List.of(sourceIssuerToken, institutionToken, sourceToken, sourceEpochToken,
                Integer.toString(identityVersion));
    }

    @Override public String toString() { return "GraphIdentityScope[redacted]"; }
}
