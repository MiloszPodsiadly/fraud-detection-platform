package com.frauddetection.enricher.graph;

/** Package-private write and lifecycle port; raw persisted-row reads live in GraphProjectionDao. */
interface GraphProjectionStore {
    GraphAppendOutcome appendVerified(GraphEvidenceAdmission.AdmittedRelation admitted);
    GraphAppendOutcome markRevokedOrFence(GraphEvidenceAdmission.AdmittedRelation admitted);
    GraphAppendOutcome reconcileLifecycle(GraphLifecycleReconciliationRequest request);
}
