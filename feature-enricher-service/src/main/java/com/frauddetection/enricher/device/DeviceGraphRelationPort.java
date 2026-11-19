package com.frauddetection.enricher.device;

import com.frauddetection.enricher.graph.GraphEvidenceRef;

import java.util.Optional;

/** Narrow owner API; every use re-resolves current Device and source state. */
public interface DeviceGraphRelationPort {
    Optional<VerifiedDeviceGraphRelation> admit(String ownerReference,
                                                DeviceGraphRelationGrant relationGrant);

    Optional<VerifiedDeviceGraphRelation> resolve(GraphEvidenceRef reference,
                                                 DeviceGraphRelationGrant relationGrant,
                                                 DeviceGraphReadGrant readGrant,
                                                 String requestingPrincipal);
}
