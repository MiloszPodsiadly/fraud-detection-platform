package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceConflictException;
import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnService;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** WebAuthn maker-checker request ledger. Approval never installs a trust file. */
@Service
public class PolicyTrustChangeService {
    private static final String REQUEST_DOMAIN = "com.frauddetection.policy-trust.change-request.v1";
    private static final String REVIEW_DOMAIN = "com.frauddetection.policy-trust.change-review.v1";
    private static final int MAX_DEPLOYMENT_OBSERVATIONS = 100;
    private static final int MAX_AUDIT_ENTRIES = 10;
    private final MongoTemplate mongo;
    private final TransactionTemplate transactions;
    private final PolicyWebAuthnService webAuthn;
    private final PolicyTrustRegistrySnapshotService registries;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public PolicyTrustChangeService(MongoTemplate mongo, MongoTransactionManager manager,
            PolicyWebAuthnService webAuthn, PolicyTrustRegistrySnapshotService registries,
            ObjectMapper mapper) {
        this(mongo, new TransactionTemplate(manager), webAuthn, registries, mapper, Clock.systemUTC());
    }

    public PolicyTrustChangeService(MongoTemplate mongo, TransactionTemplate transactions,
            PolicyWebAuthnService webAuthn, PolicyTrustRegistrySnapshotService registries,
            ObjectMapper mapper, Clock clock) {
        this.mongo = mongo;
        this.transactions = transactions;
        this.webAuthn = webAuthn;
        this.registries = registries;
        this.mapper = mapper;
        this.clock = clock;
    }

    @PostConstruct
    void ensureIndexes() {
        mongo.indexOps(PolicyTrustChangeDocument.class).ensureIndex(new Index()
                .on("intent.registry", Sort.Direction.ASC)
                .on("intent.requestedGeneration", Sort.Direction.ASC)
                .on("status", Sort.Direction.ASC)
                .named("policy_trust_pending_generation_v1"));
        mongo.indexOps(PolicyTrustChangeAuditDocument.class).ensureIndex(new Index()
                .on("requestId", Sort.Direction.ASC).on("occurredAt", Sort.Direction.ASC)
                .named("policy_trust_change_audit_v1"));
        mongo.indexOps(PolicyTrustInstanceObservation.class).ensureIndex(new Index()
                .on("registry", Sort.Direction.ASC).on("observedAt", Sort.Direction.DESC)
                .named("policy_trust_instance_observation_v1"));
        mongo.indexOps(PolicyTrustInstanceObservation.class).ensureIndex(new Index()
                .on("observedAt", Sort.Direction.DESC)
                .named("policy_trust_instance_observed_at_v1"));
    }

    public PolicyWebAuthnService.CeremonyOptions startRequest(PolicyGovernanceActor actor,
            String operationId, Intent intent) {
        requireActor(actor, ModelPolicyAuthority.TRUST_REQUEST, intent);
        validate(intent);
        var base = base(intent);
        return webAuthn.startBoundAssertion(actor, "trust-request", operationId,
                requestContract(intent, base));
    }

    public PolicyTrustChangeDocument finishRequest(PolicyGovernanceActor actor,
            String operationId, Intent intent, String challengeId, String responseJson) {
        requireActor(actor, ModelPolicyAuthority.TRUST_REQUEST, intent);
        validate(intent);
        if (operationId == null || !String.valueOf(challengeId).equals("trust-request:" + operationId)) {
            throw new PolicyGovernanceConflictException("Trust request operation changed after step-up");
        }
        var base = base(intent);
        return transactions.execute(ignored -> {
            if (mongo.findById(operationId, PolicyTrustChangeDocument.class) != null) {
                throw new PolicyGovernanceConflictException("Trust change operation already committed");
            }
            var proof = webAuthn.acceptBoundAssertion(actor, "trust-request", challengeId,
                    responseJson, requestContract(intent, base));
            String digest = GovernanceCanonicalJson.sha256(mapper, intent);
            var document = new PolicyTrustChangeDocument(operationId, intent, digest,
                    base.generation(), base.digestSha256(), actor.id(), proof.credentialId(),
                    proof.assertionSha256(), proof.acceptedAt(), "PENDING_REVIEW",
                    null, null, null, null);
            mongo.insert(document);
            mongo.insert(new PolicyTrustChangeAuditDocument(operationId + ":requested", operationId,
                    "REQUESTED", actor.id(), digest, proof.assertionSha256(), proof.acceptedAt()));
            return document;
        });
    }

    public PolicyWebAuthnService.CeremonyOptions startReview(PolicyGovernanceActor actor,
            String requestId, String operationId, Decision decision) {
        if (decision == null) throw new IllegalArgumentException("Exact trust decision required");
        PolicyTrustChangeDocument current = pending(requestId);
        requireIndependentReviewer(actor, current);
        return webAuthn.startBoundAssertion(actor, "trust-review", operationId,
                reviewContract(current, decision));
    }

    public PolicyTrustChangeDocument finishReview(PolicyGovernanceActor actor,
            String requestId, Decision decision, String challengeId, String responseJson) {
        if (decision == null) throw new IllegalArgumentException("Exact trust decision required");
        return transactions.execute(ignored -> {
            PolicyTrustChangeDocument current = pending(requestId);
            requireIndependentReviewer(actor, current);
            var proof = webAuthn.acceptBoundAssertion(actor, "trust-review", challengeId,
                    responseJson, reviewContract(current, decision));
            var updated = mongo.findAndModify(Query.query(Criteria.where("_id").is(requestId)
                            .and("status").is("PENDING_REVIEW").and("intentSha256").is(current.intentSha256())),
                    new Update().set("status", decision == Decision.APPROVE
                                    ? "PENDING_OPERATOR_ACTION" : "REJECTED")
                            .set("reviewedBy", actor.id()).set("reviewerCredentialId", proof.credentialId())
                            .set("reviewAssertionSha256", proof.assertionSha256())
                            .set("reviewedAt", proof.acceptedAt()),
                    FindAndModifyOptions.options().returnNew(true), PolicyTrustChangeDocument.class);
            if (updated == null) throw new PolicyGovernanceConflictException("Trust change was reviewed concurrently");
            mongo.insert(new PolicyTrustChangeAuditDocument(requestId + ":reviewed", requestId,
                    decision.name(), actor.id(), current.intentSha256(), proof.assertionSha256(),
                    proof.acceptedAt()));
            return updated;
        });
    }

    public List<PolicyTrustChangeDocument> recent(PolicyGovernanceActor actor) {
        if (actor == null || !actor.authorities().contains(ModelPolicyAuthority.TRUST_READ)) {
            throw new AccessDeniedException("Institutional trust read mandate required");
        }
        Query query = new Query();
        if (!actor.authorities().contains(ModelPolicyAuthority.PRODUCTION)) {
            query.addCriteria(Criteria.where("intent.environment").ne(ModelGovernanceEnvironment.PRODUCTION));
        }
        return List.copyOf(mongo.find(query.with(Sort.by(Sort.Direction.DESC, "requestedAt"))
                .limit(50), PolicyTrustChangeDocument.class));
    }

    public List<PolicyTrustChangeAuditDocument> audit(PolicyGovernanceActor actor, String requestId) {
        PolicyTrustChangeDocument current = mongo.findById(requestId, PolicyTrustChangeDocument.class);
        if (current == null || actor == null || !actor.authorities().contains(ModelPolicyAuthority.TRUST_READ)
                || current.intent().environment() == ModelGovernanceEnvironment.PRODUCTION
                    && !actor.authorities().contains(ModelPolicyAuthority.PRODUCTION)) {
            throw new AccessDeniedException("Trust change audit unavailable in this scope");
        }
        return List.copyOf(mongo.find(Query.query(Criteria.where("requestId").is(requestId))
                .with(Sort.by(Sort.Direction.ASC, "occurredAt")).limit(MAX_AUDIT_ENTRIES),
                PolicyTrustChangeAuditDocument.class));
    }

    public DeploymentView deployment(PolicyGovernanceActor actor, String requestId) {
        PolicyTrustChangeDocument request = mongo.findById(requestId, PolicyTrustChangeDocument.class);
        if (request == null || actor == null || !actor.authorities().contains(ModelPolicyAuthority.TRUST_READ)
                || request.intent().environment() == ModelGovernanceEnvironment.PRODUCTION
                    && !actor.authorities().contains(ModelPolicyAuthority.PRODUCTION)) {
            throw new AccessDeniedException("Trust deployment state unavailable in this scope");
        }
        var current = registries.snapshot(request.intent().registry());
        Instant freshAfter = clock.instant().minusSeconds(90);
        List<PolicyTrustInstanceObservation> queried = mongo.find(Query.query(Criteria.where("registry")
                        .is(request.intent().registry())).with(Sort.by(Sort.Direction.DESC, "observedAt"))
                .limit(MAX_DEPLOYMENT_OBSERVATIONS + 1), PolicyTrustInstanceObservation.class);
        boolean observationsTruncated = queried.size() > MAX_DEPLOYMENT_OBSERVATIONS;
        List<PolicyTrustInstanceObservation> all = queried.stream()
                .limit(MAX_DEPLOYMENT_OBSERVATIONS).toList();
        List<InstanceAcknowledgement> instances = all.stream()
                .map(observation -> new InstanceAcknowledgement(observation.role(), observation.instanceId(),
                        observation.generation(), observation.digestSha256(), observation.observedAt(),
                        observation.observedAt().isAfter(freshAfter)))
                .toList();
        var fresh = instances.stream().filter(InstanceAcknowledgement::fresh).toList();
        boolean alert = fresh.stream().anyMatch(entry -> "ALERT_VERIFIER".equals(entry.role()));
        boolean publisher = fresh.stream().anyMatch(entry -> "PUBLISHER".equals(entry.role()));
        boolean exact = fresh.stream().allMatch(entry ->
                entry.generation() == request.intent().requestedGeneration()
                        && request.intent().candidateRegistrySha256().equals(entry.digestSha256()));
        boolean approvedForOperatorAction = "PENDING_OPERATOR_ACTION".equals(request.status());
        String state = observationsTruncated ? "OBSERVATION_SET_TRUNCATED"
                : !approvedForOperatorAction ? "REQUEST_NOT_APPROVED"
                : fresh.isEmpty() ? all.isEmpty() ? "NO_ACKNOWLEDGEMENT" : "STALE"
                : !exact ? "MISMATCH_OR_PARTIAL_ROLLOUT"
                : !alert || !publisher ? "PARTIAL_ROLLOUT" : "OBSERVED_CONSISTENT_PENDING_OPERATOR_CONFIRMATION";
        return new DeploymentView(request.id(), request.status(), request.intent().registry(),
                request.intent().requestedGeneration(), request.intent().candidateRegistrySha256(),
                current.rollbackFloor(), state,
                approvedForOperatorAction && !observationsTruncated && exact && alert && publisher
                        ? request.intent().requestedGeneration() : null,
                approvedForOperatorAction && !observationsTruncated && exact && alert && publisher
                        ? request.intent().candidateRegistrySha256() : null,
                observationsTruncated, instances);
    }

    private PolicyTrustChangeDocument pending(String requestId) {
        if (requestId == null || !requestId.matches("^[A-Za-z0-9._:-]{1,128}$")) {
            throw new IllegalArgumentException("Valid trust request ID required");
        }
        PolicyTrustChangeDocument current = mongo.findById(requestId, PolicyTrustChangeDocument.class);
        if (current == null || !"PENDING_REVIEW".equals(current.status())
                || !current.intentSha256().equals(GovernanceCanonicalJson.sha256(mapper, current.intent()))) {
            throw new PolicyGovernanceConflictException("Pending exact trust change unavailable");
        }
        var base = base(current.intent());
        if (base.generation() != current.baseGeneration()
                || !base.digestSha256().equals(current.baseRegistrySha256())) {
            throw new PolicyGovernanceConflictException("Trust registry changed during independent review");
        }
        return current;
    }

    private void requireActor(PolicyGovernanceActor actor, String authority, Intent intent) {
        if (actor == null || !actor.authorities().contains(authority)
                || actor.issuer() == null || actor.subject() == null
                || !actor.id().equals(PolicyGovernanceActor.identityId(actor.issuer(), actor.subject()))) {
            throw new AccessDeniedException("Dedicated trust administration mandate required");
        }
        if (intent != null && intent.environment() == ModelGovernanceEnvironment.PRODUCTION
                && !actor.authorities().contains(ModelPolicyAuthority.PRODUCTION)) {
            throw new AccessDeniedException("Production trust scope mandate required");
        }
        if (intent != null && intent.registry() == PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS
                && actor.id().equals(intent.targetIdentity())) {
            throw new AccessDeniedException("Operator cannot change own mandate");
        }
    }

    private void requireIndependentReviewer(PolicyGovernanceActor actor, PolicyTrustChangeDocument current) {
        requireActor(actor, ModelPolicyAuthority.TRUST_APPROVE, current.intent());
        if (actor.id().equals(current.requestedBy())) {
            throw new AccessDeniedException("Trust change requires a different reviewer");
        }
    }

    private void validate(Intent intent) {
        if (intent == null || intent.registry() == null || intent.action() == null
                || intent.environment() == null || intent.intendedUse() == null
                || intent.requestedGeneration() < 2
                || !sha(intent.candidateRegistrySha256())
                || intent.targetIdentity() == null || intent.targetIdentity().length() > 128
                || !intent.targetIdentity().matches("^[A-Za-z0-9._:-]+$")
                || intent.caseReference() == null || intent.caseReference().isBlank()
                || intent.caseReference().length() > 128 || intent.roles() == null
                || intent.roles().size() > 4 || intent.roles().stream().anyMatch(Objects::isNull)
                || intent.roles().stream().distinct().count() != intent.roles().size()) {
            throw new IllegalArgumentException("Structured trust change request is invalid");
        }
        if (intent.registry() == PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS) {
            if (!intent.targetIdentity().matches("^oidc:[0-9a-f]{64}$")
                    || !List.of(Action.GRANT, Action.REVOKE).contains(intent.action())
                    || intent.keyId() != null || intent.publicKeySha256() != null
                    || intent.publicKeySpkiBase64() != null
                    || intent.roles().isEmpty() || !List.of("POLICY_AUTHOR", "POLICY_APPROVER")
                            .containsAll(intent.roles())) {
                throw new IllegalArgumentException("Mandate change exceeds supported actor policy");
            }
        } else if (!List.of(Action.ADD, Action.ROTATE, Action.RETIRE, Action.REVOKE)
                .contains(intent.action()) || intent.keyId() == null
                || !intent.keyId().matches("^[A-Za-z0-9._:-]{1,128}$")
                || !sha(intent.publicKeySha256())
                || intent.roles().isEmpty()
                || !((intent.registry() == PolicyTrustRegistrySnapshotService.Registry.ISSUER_KEYS
                        ? List.of("POLICY_ISSUER")
                        : List.of("POLICY_AUTHOR", "POLICY_APPROVER", "POLICY_PUBLISHER"))
                            .containsAll(intent.roles()))) {
            throw new IllegalArgumentException("Key change exceeds supported trust policy");
        }
        var base = base(intent);
        if (intent.registry() != PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS) {
            if (intent.action() == Action.ADD || intent.action() == Action.ROTATE) {
                try {
                    ModelPromotionTrustedIssuerRegistry.publicKey(intent.publicKeySpkiBase64(),
                            intent.publicKeySha256());
                } catch (Exception invalid) {
                    throw new IllegalArgumentException("New public key fingerprint is invalid", invalid);
                }
            } else if (intent.publicKeySpkiBase64() != null || base.entries().stream().noneMatch(entry ->
                    intent.targetIdentity().equals(entry.identity())
                            && intent.keyId().equals(entry.keyId())
                            && intent.publicKeySha256().equals(entry.publicKeySha256()))) {
                throw new IllegalArgumentException("Retirement or revocation must identify an existing public key");
            }
        }
        if (intent.requestedGeneration() != base.generation() + 1
                || intent.candidateRegistrySha256().equals(base.digestSha256())) {
            throw new IllegalArgumentException("Trust generation or candidate digest does not advance");
        }
    }

    private boolean sha(String digest) {
        return digest != null && digest.matches("^[0-9a-f]{64}$");
    }

    private PolicyTrustRegistrySnapshotService.RegistryView base(Intent intent) {
        return registries.requireReady(intent.registry());
    }

    private Map<String, Object> requestContract(Intent intent,
            PolicyTrustRegistrySnapshotService.RegistryView base) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("domain", REQUEST_DOMAIN);
        contract.put("intentSha256", GovernanceCanonicalJson.sha256(mapper, intent));
        contract.put("registry", intent.registry().name());
        contract.put("baseGeneration", base.generation());
        contract.put("baseRegistrySha256", base.digestSha256());
        contract.put("requestedGeneration", intent.requestedGeneration());
        contract.put("candidateRegistrySha256", intent.candidateRegistrySha256());
        return contract;
    }

    private Map<String, Object> reviewContract(PolicyTrustChangeDocument current, Decision decision) {
        return Map.of("domain", REVIEW_DOMAIN, "requestId", current.id(),
                "intentSha256", current.intentSha256(), "baseGeneration", current.baseGeneration(),
                "baseRegistrySha256", current.baseRegistrySha256(),
                "candidateRegistrySha256", current.intent().candidateRegistrySha256(),
                "requestedGeneration", current.intent().requestedGeneration(), "decision", decision.name());
    }

    public enum Action { GRANT, ADD, ROTATE, RETIRE, REVOKE }
    public enum Decision { APPROVE, REJECT }
    public record InstanceAcknowledgement(String role, String instanceId, long generation,
            String digestSha256, Instant observedAt, boolean fresh) { }
    public record DeploymentView(String requestId, String requestStatus,
            PolicyTrustRegistrySnapshotService.Registry registry, long requestedGeneration,
            String candidateRegistrySha256, long rollbackFloor, String rolloutState,
            Long consistentObservedGeneration, String consistentObservedRegistrySha256,
            boolean observationSetTruncated,
            List<InstanceAcknowledgement> acknowledgements) { }
    public record Intent(PolicyTrustRegistrySnapshotService.Registry registry, Action action,
            String targetIdentity, String keyId, String publicKeySha256,
            String publicKeySpkiBase64, List<String> roles,
            ModelGovernanceEnvironment environment, ModelPromotionIntendedUse intendedUse,
            long requestedGeneration, String candidateRegistrySha256, String caseReference) { }
}
