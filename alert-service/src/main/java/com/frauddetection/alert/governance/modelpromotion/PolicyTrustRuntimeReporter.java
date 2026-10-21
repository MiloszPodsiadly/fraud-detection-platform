package com.frauddetection.alert.governance.modelpromotion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/** Reports immutable startup-loaded verifier trust; changing a mounted file alone is not an acknowledgement. */
@Component
public class PolicyTrustRuntimeReporter {
    private static final Logger log = LoggerFactory.getLogger(PolicyTrustRuntimeReporter.class);
    private final MongoTemplate mongo;
    private final ModelPromotionPolicyAuthorityVerifier issuers;
    private final ModelPromotionPolicyApprovalEvidenceVerifier approvals;
    private final String instanceId = UUID.randomUUID().toString();

    public PolicyTrustRuntimeReporter(MongoTemplate mongo,
            ModelPromotionPolicyAuthorityVerifier issuers,
            ModelPromotionPolicyApprovalEvidenceVerifier approvals) {
        this.mongo = mongo;
        this.issuers = issuers;
        this.approvals = approvals;
    }

    @Scheduled(initialDelayString = "${app.model-promotion.trust-observation.initial-delay:PT5S}",
            fixedDelayString = "${app.model-promotion.trust-observation.delay:PT30S}")
    public void report() {
        try {
            if (issuers instanceof Ed25519ModelPromotionPolicyAuthorityVerifier issuerVerifier) {
                var loaded = issuerVerifier.loadedRegistry();
                record(mongo, "ALERT_VERIFIER", instanceId,
                        PolicyTrustRegistrySnapshotService.Registry.ISSUER_KEYS,
                        loaded.generation(), loaded.canonicalSha256(), Clock.systemUTC());
            }
            if (approvals instanceof MongoModelPromotionPolicyApprovalEvidenceVerifier approvalVerifier) {
                var approval = approvalVerifier.loadedApprovalTrust();
                record(mongo, "ALERT_VERIFIER", instanceId,
                        PolicyTrustRegistrySnapshotService.Registry.APPROVAL_KEYS,
                        approval.generation(), approval.canonicalSha256(), Clock.systemUTC());
                var actors = approvalVerifier.loadedActorTrust();
                if (actors != null) record(mongo, "ALERT_VERIFIER", instanceId,
                        PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS,
                        actors.generation(), actors.canonicalSha256(), Clock.systemUTC());
            }
        } catch (RuntimeException failure) {
            log.warn("Policy trust runtime observation unavailable: {}", failure.getClass().getSimpleName());
        }
    }

    static void record(MongoTemplate mongo, String role, String instanceId,
            PolicyTrustRegistrySnapshotService.Registry registry, long generation,
            String digest, Clock clock) {
        mongo.save(new PolicyTrustInstanceObservation(role + ":" + instanceId + ":" + registry,
                role, instanceId, registry, generation, digest, clock.instant()));
    }
}
