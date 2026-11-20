package com.frauddetection.enricher.graph;

/** Package-private write and lifecycle port; raw persisted-row reads live in GraphProjectionDao. */
interface GraphProjectionStore {
    GraphAppendOutcome appendVerified(GraphEvidenceAdmission.AdmittedRelation admitted);
    /** Uses only the original short admission authority; later changes require fresh reconciliation. */
    GraphAppendOutcome fenceWithinAdmissionOperation(GraphEvidenceAdmission.AdmittedRelation admitted);
    GraphAppendOutcome reconcileLifecycle(GraphLifecycleReconciliationRequest request);
}
