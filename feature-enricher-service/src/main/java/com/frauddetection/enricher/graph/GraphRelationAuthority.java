package com.frauddetection.enricher.graph;

/** Graph admission can only request an owner resolution; it cannot mint or supply domain evidence. */
interface GraphRelationAuthority {
    GraphRelationResolution resolveForAdmission(String ownerReference);
    GraphRelationResolution revalidate(GraphEvidenceRef expected, String requestingPrincipal);
}
