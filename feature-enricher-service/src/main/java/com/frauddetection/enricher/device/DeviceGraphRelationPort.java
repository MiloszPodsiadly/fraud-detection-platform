package com.frauddetection.enricher.device;

import com.frauddetection.enricher.graph.GraphEvidenceRef;

import java.time.Duration;

/** Narrow owner API; every use re-resolves current Device and source state. */
public interface DeviceGraphRelationPort {
    DeviceGraphRelationResolution admit(String ownerReference,
                                        DeviceGraphRelationGrant relationGrant);

    DeviceGraphRelationResolution resolve(GraphEvidenceRef reference,
                                          DeviceGraphRelationGrant relationGrant,
                                          DeviceGraphReadGrant readGrant,
                                          String requestingPrincipal);

    /** Bounded caller path; adapters without explicit budget support fail closed. */
    default DeviceGraphRelationResolution resolve(GraphEvidenceRef reference,
                                                   DeviceGraphRelationGrant relationGrant,
                                                   DeviceGraphReadGrant readGrant,
                                                   String requestingPrincipal,
                                                   Duration remainingBudget) {
        if (remainingBudget == null || remainingBudget.isZero() || remainingBudget.isNegative()) {
            return DeviceGraphRelationResolution.unavailable(
                    com.frauddetection.enricher.graph.GraphReadReason.DEADLINE_EXCEEDED);
        }
        return DeviceGraphRelationResolution.unavailable(
                com.frauddetection.enricher.graph.GraphReadReason.NOT_SUPPORTED);
    }
}
