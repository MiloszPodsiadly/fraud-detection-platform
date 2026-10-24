package com.frauddetection.alert.governance.modelpromotion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.util.UUID;

/** Reports immutable startup-loaded verifier trust; changing a mounted file alone is not an acknowledgement. */
@Component
public class PolicyTrustRuntimeReporter {
    private static final Logger log = LoggerFactory.getLogger(PolicyTrustRuntimeReporter.class);
    private final MongoTemplate mongo;
    private final ModelPromotionPolicyAuthorityVerifier issuers;
    private final ModelPromotionPolicyApprovalEvidenceVerifier approvals;
    private final String instanceId;
    private final String deploymentId;
    private final PolicyTrustObservationProof proof;
    private final PrivateKey privateKey;
    private final String keyId;
    private final String processEpoch = UUID.randomUUID().toString();
    private final Instant processStartedAt = Clock.systemUTC().instant();

    public PolicyTrustRuntimeReporter(MongoTemplate mongo,
            ModelPromotionPolicyAuthorityVerifier issuers,
            ModelPromotionPolicyApprovalEvidenceVerifier approvals,
            @Value("${MODEL_PROMOTION_TRUST_INSTANCE_ID:}") String configuredInstanceId,
            @Value("${MODEL_PROMOTION_TRUST_DEPLOYMENT_ID:}") String deploymentId,
            @Value("${MODEL_PROMOTION_OBSERVATION_KEYS_PATH:}") String keysPath,
            @Value("${MODEL_PROMOTION_OBSERVATION_PRIVATE_KEY_PATH:}") String privateKeyPath,
            @Value("${MODEL_PROMOTION_OBSERVATION_KEY_ID:}") String keyId) {
        this.mongo = mongo;
        this.issuers = issuers;
        this.approvals = approvals;
        this.instanceId = configuredInstanceId == null || configuredInstanceId.isBlank()
                ? UUID.randomUUID().toString() : configuredInstanceId;
        this.deploymentId = deploymentId;
        this.proof = keysPath == null || keysPath.isBlank() ? null
                : PolicyTrustObservationProof.load(Path.of(keysPath));
        this.privateKey = privateKeyPath == null || privateKeyPath.isBlank() ? null
                : PolicyTrustObservationProof.privateKey(Path.of(privateKeyPath));
        this.keyId = keyId;
    }

    @Scheduled(initialDelayString = "${app.model-promotion.trust-observation.initial-delay:PT5S}",
            fixedDelayString = "${app.model-promotion.trust-observation.delay:PT30S}")
    public void report() {
        try {
            if (issuers instanceof Ed25519ModelPromotionPolicyAuthorityVerifier issuerVerifier) {
                var loaded = issuerVerifier.loadedRegistry();
                record(mongo, "ALERT_VERIFIER", instanceId, deploymentId, processEpoch, processStartedAt,
                        PolicyTrustRegistrySnapshotService.Registry.ISSUER_KEYS,
                        loaded.generation(), loaded.canonicalSha256(), Clock.systemUTC(), proof, keyId, privateKey);
            }
            if (approvals instanceof MongoModelPromotionPolicyApprovalEvidenceVerifier approvalVerifier) {
                var approval = approvalVerifier.loadedApprovalTrust();
                record(mongo, "ALERT_VERIFIER", instanceId, deploymentId, processEpoch, processStartedAt,
                        PolicyTrustRegistrySnapshotService.Registry.APPROVAL_KEYS,
                        approval.generation(), approval.canonicalSha256(), Clock.systemUTC(), proof, keyId, privateKey);
                var actors = approvalVerifier.loadedActorTrust();
                if (actors != null) record(mongo, "ALERT_VERIFIER", instanceId,
                        deploymentId, processEpoch, processStartedAt,
                        PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS,
                        actors.generation(), actors.canonicalSha256(), Clock.systemUTC(), proof, keyId, privateKey);
            }
        } catch (RuntimeException failure) {
            log.warn("Policy trust runtime observation unavailable: {}", failure.getClass().getSimpleName());
        }
    }

    static void record(MongoTemplate mongo, String role, String instanceId, String deploymentId,
            String processEpoch, Instant processStartedAt,
            PolicyTrustRegistrySnapshotService.Registry registry, long generation,
            String digest, Clock clock, PolicyTrustObservationProof proof,
            String keyId, PrivateKey privateKey) {
        if (proof == null || privateKey == null || keyId == null || keyId.isBlank()) return;
        var report = new PolicyTrustInstanceObservation(role + ":" + instanceId + ":" + processEpoch + ":" + registry,
                role, instanceId, registry, generation, digest, clock.instant(),
                deploymentId, processEpoch, processStartedAt);
        mongo.save(proof.sign(report, keyId, privateKey));
    }
}
