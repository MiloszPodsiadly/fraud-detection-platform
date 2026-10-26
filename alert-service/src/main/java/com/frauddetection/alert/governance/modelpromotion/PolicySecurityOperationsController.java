package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.governance.modelpolicy.PolicyPublicationJobDocument;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Safe diagnostics: no signing keys, trust-file paths, connection strings or raw job payloads. */
@RestController
@RequestMapping("/api/v1/governance/policy-security")
public class PolicySecurityOperationsController {
    private static final int MAX_TRUST_OBSERVATIONS = 1_000;
    private final MongoTemplate mongo;
    private final PolicyTrustRegistrySnapshotService registries;
    private final String oidcIssuer;
    private final String rpId;
    private final String origin;
    private final Clock clock;

    @Autowired
    public PolicySecurityOperationsController(MongoTemplate mongo, PolicyTrustRegistrySnapshotService registries,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String oidcIssuer,
            @Value("${app.model-policy-webauthn.rp-id:}") String rpId,
            @Value("${app.model-policy-webauthn.origin:}") String origin) {
        this(mongo, registries, oidcIssuer, rpId, origin, Clock.systemUTC());
    }

    PolicySecurityOperationsController(MongoTemplate mongo, PolicyTrustRegistrySnapshotService registries,
            String oidcIssuer, String rpId, String origin, Clock clock) {
        this.mongo = mongo;
        this.registries = registries;
        this.oidcIssuer = oidcIssuer;
        this.rpId = rpId;
        this.origin = origin;
        this.clock = clock;
    }

    @GetMapping("/operations")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.POLICY_SECURITY_OPERATIONS,
            resourceType = ReadAccessResourceType.POLICY_SECURITY)
    public Diagnostics operations(Authentication authentication) {
        var actor = PolicyGovernanceActor.from(authentication);
        if (!actor.authorities().contains(ModelPolicyAuthority.PRODUCTION)
                || !actor.authorities().contains(ModelPolicyAuthority.TRUST_READ)) {
            throw new AccessDeniedException("Production trust diagnostics mandate required");
        }
        try {
            Document hello = mongo.getDb().runCommand(new Document("hello", 1));
            String transactions = hello.getString("setName") != null
                    && hello.get("logicalSessionTimeoutMinutes") != null
                    && Boolean.TRUE.equals(hello.getBoolean("isWritablePrimary"))
                    ? "CAPABLE" : "UNAVAILABLE";
            long blocked = mongo.count(Query.query(Criteria.where("state")
                    .is(PolicyPublicationJobDocument.State.BLOCKED)), PolicyPublicationJobDocument.class);
            Instant freshAfter = clock.instant().minusSeconds(90);
            List<PolicyTrustInstanceObservation> queried = mongo.find(Query.query(
                            Criteria.where("observedAt").gt(freshAfter))
                    .with(Sort.by(Sort.Direction.DESC, "observedAt"))
                    .limit(MAX_TRUST_OBSERVATIONS + 1), PolicyTrustInstanceObservation.class);
            boolean observationsTruncated = queried.size() > MAX_TRUST_OBSERVATIONS;
            List<PolicyTrustInstanceObservation> observations = queried.stream()
                    .limit(MAX_TRUST_OBSERVATIONS).toList();
            String worker = observations.stream().anyMatch(item -> "PUBLISHER".equals(item.role()))
                    ? "HEARTBEAT_OBSERVED" : "NO_FRESH_HEARTBEAT";
            String trust = observationsTruncated ? "OBSERVATION_SET_TRUNCATED" : "UNOBSERVED";
            if (!observationsTruncated && !observations.isEmpty()) {
                boolean consistent = true;
                for (var registry : PolicyTrustRegistrySnapshotService.Registry.values()) {
                    var loaded = registries.snapshot(registry);
                    var current = observations.stream().filter(item -> item.registry() == registry).toList();
                    if (!"READY".equals(loaded.status()) || current.stream().noneMatch(item -> "PUBLISHER".equals(item.role()))
                            || current.stream().noneMatch(item -> "ALERT_VERIFIER".equals(item.role()))
                            || current.stream().anyMatch(item -> item.generation() != loaded.generation()
                                    || !item.digestSha256().equals(loaded.digestSha256()))) consistent = false;
                }
                trust = consistent ? "MATCHING_HEARTBEATS_MEMBERSHIP_UNVERIFIED" : "PARTIAL_OR_MISMATCHED";
            }
            String config = oidcIssuer != null && !oidcIssuer.isBlank()
                    && rpId != null && !rpId.isBlank() && origin != null && !origin.isBlank()
                    ? "CONFIGURED_NOT_PROBED" : "INCOMPLETE";
            return new Diagnostics(blocked, worker, transactions, config, trust, clock.instant());
        } catch (RuntimeException unavailable) {
            return new Diagnostics(null, "UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN", clock.instant());
        }
    }

    public record Diagnostics(Long blockedPublicationJobs, String signingWorkerAvailability,
            String mongoTransactionReadiness, String oidcWebAuthnConfiguration,
            String trustRegistryConsistency, Instant checkedAt) { }
}
