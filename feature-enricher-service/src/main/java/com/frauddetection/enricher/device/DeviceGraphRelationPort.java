package com.frauddetection.enricher.device;

import com.frauddetection.enricher.graph.GraphEvidenceRef;

/** Narrow owner API; every use re-resolves current Device and source state. */
public interface DeviceGraphRelationPort {
    DeviceGraphRelationResolution admit(String ownerReference,
                                        DeviceGraphRelationGrant relationGrant);

    DeviceGraphRelationResolution resolve(GraphEvidenceRef reference,
                                          DeviceGraphRelationGrant relationGrant,
                                          DeviceGraphReadGrant readGrant,
                                          String requestingPrincipal);
}
