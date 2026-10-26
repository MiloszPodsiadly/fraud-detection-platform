package com.frauddetection.alert.governance.modelpromotion;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Operational fail-closed gate for new signatures; observations are telemetry, not attestation. */
public final class PolicyTrustFleetGuard {
    private final MongoTemplate mongo;
    private final PolicyTrustFleetConsistency.Membership membership;
    private final Map<PolicyTrustRegistrySnapshotService.Registry, ExpectedRegistry> loaded;
    private final Clock clock;
    private final PolicyTrustObservationProof proof;

    public PolicyTrustFleetGuard(MongoTemplate mongo, PolicyTrustFleetConsistency.Membership membership,
            Map<PolicyTrustRegistrySnapshotService.Registry, ExpectedRegistry> loaded, Clock clock,
            PolicyTrustObservationProof proof) {
        this.mongo = mongo;
        this.membership = membership;
        this.loaded = Map.copyOf(loaded);
        this.clock = clock;
        this.proof = proof;
    }

    public boolean consistent() {
        if (proof == null || !membership.complete()
                || loaded.size() != PolicyTrustRegistrySnapshotService.Registry.values().length) return false;
        try {
            for (var registry : PolicyTrustRegistrySnapshotService.Registry.values()) {
                ExpectedRegistry expected = loaded.get(registry);
                if (expected == null || expected.generation() < expected.rollbackFloor()) return false;
                List<PolicyTrustInstanceObservation> queried = mongo.find(Query.query(Criteria.where("registry")
                                .is(registry).and("deploymentId").is(membership.deploymentId()))
                        .with(Sort.by(Sort.Direction.DESC, "observedAt"))
                        .limit(PolicyTrustFleetConsistency.MAX_OBSERVATIONS + 1),
                        PolicyTrustInstanceObservation.class);
                boolean truncated = queried.size() > PolicyTrustFleetConsistency.MAX_OBSERVATIONS;
                Instant now = clock.instant();
                if (queried.stream().anyMatch(observation -> observation.observedAt() == null
                        || (!observation.observedAt().isBefore(now.minusSeconds(90))
                            && !observation.observedAt().isAfter(now.plusSeconds(5))
                            && !proof.verify(observation)))) return false;
                var assessment = PolicyTrustFleetConsistency.assess(membership,
                        queried.stream().limit(PolicyTrustFleetConsistency.MAX_OBSERVATIONS).toList(),
                        expected.generation(), expected.digestSha256(), expected.rollbackFloor(),
                        now, truncated);
                if (!"OBSERVED_CONSISTENT".equals(assessment.rolloutState())) return false;
            }
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public record ExpectedRegistry(long generation, String digestSha256, long rollbackFloor) { }
}
