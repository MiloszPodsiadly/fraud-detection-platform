package com.frauddetection.alert.governance.modelpromotion;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** A service's report of trust actually loaded in memory, not a browser-supplied activation claim. */
@Document(PolicyTrustInstanceObservation.COLLECTION)
public record PolicyTrustInstanceObservation(@Id String id, String role, String instanceId,
        PolicyTrustRegistrySnapshotService.Registry registry, long generation,
        String digestSha256, Instant observedAt, String deploymentId,
        String processEpoch, Instant processStartedAt) {
    public static final String COLLECTION = "model_policy_trust_instance_observations";

    /** Legacy observations remain readable but cannot establish process or rollout identity. */
    public PolicyTrustInstanceObservation(String id, String role, String instanceId,
            PolicyTrustRegistrySnapshotService.Registry registry, long generation,
            String digestSha256, Instant observedAt) {
        this(id, role, instanceId, registry, generation, digestSha256, observedAt, null, null, null);
    }
}
