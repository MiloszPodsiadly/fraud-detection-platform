package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceConflictException;
import com.frauddetection.alert.governance.modelpolicy.GovernanceBoundWebAuthnProof;
import com.frauddetection.alert.governance.modelpolicy.GovernanceBoundWebAuthnProofVerifier;
import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnService;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    private static final int MAX_DEPLOYMENT_OBSERVATIONS = PolicyTrustFleetConsistency.MAX_OBSERVATIONS;
    private static final int MAX_AUDIT_ENTRIES = 10;
    private final MongoTemplate mongo;
    private final TransactionTemplate transactions;
    private final PolicyWebAuthnService webAuthn;
    private final PolicyTrustRegistrySnapshotService registries;
    private final PolicyTrustCandidateBuilder candidates;
    private final ObjectMapper mapper;
    private final GovernanceBoundWebAuthnProofVerifier historicalVerifier;
    private final Clock clock;
    private final PolicyTrustFleetConsistency.Membership membership;

    @Autowired
    public PolicyTrustChangeService(MongoTemplate mongo, MongoTransactionManager manager,
            PolicyWebAuthnService webAuthn, PolicyTrustRegistrySnapshotService registries,
            ObjectMapper mapper,
            @Value("${MODEL_PROMOTION_TRUST_DEPLOYMENT_ID:}") String deploymentId,
            @Value("${MODEL_PROMOTION_TRUST_EXPECTED_ALERT_INSTANCES:}") String expectedAlerts,
            @Value("${MODEL_PROMOTION_TRUST_EXPECTED_PUBLISHER_INSTANCES:}") String expectedPublishers) {
        this(mongo, new TransactionTemplate(manager), webAuthn, registries, mapper, Clock.systemUTC(),
                PolicyTrustFleetConsistency.membership(deploymentId, expectedAlerts, expectedPublishers));
    }

    public PolicyTrustChangeService(MongoTemplate mongo, TransactionTemplate transactions,
            PolicyWebAuthnService webAuthn, PolicyTrustRegistrySnapshotService registries,
            ObjectMapper mapper, Clock clock) {
        this(mongo, transactions, webAuthn, registries, mapper, clock,
                PolicyTrustFleetConsistency.membership(null, null, null));
    }

    public PolicyTrustChangeService(MongoTemplate mongo, TransactionTemplate transactions,
            PolicyWebAuthnService webAuthn, PolicyTrustRegistrySnapshotService registries,
            ObjectMapper mapper, Clock clock, PolicyTrustFleetConsistency.Membership membership) {
        this.mongo = mongo;
        this.transactions = transactions;
        this.webAuthn = webAuthn;
        this.registries = registries;
        this.candidates = new PolicyTrustCandidateBuilder(registries, mapper, clock);
        this.mapper = mapper;
        this.historicalVerifier = new GovernanceBoundWebAuthnProofVerifier(mapper);
        this.clock = clock;
        this.membership = membership;
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
                .on("registry", Sort.Direction.ASC).on("deploymentId", Sort.Direction.ASC)
                .on("observedAt", Sort.Direction.DESC)
                .named("policy_trust_deployment_observation_v1"));
        mongo.indexOps(PolicyTrustInstanceObservation.class).ensureIndex(new Index()
                .on("observedAt", Sort.Direction.DESC)
                .named("policy_trust_instance_observed_at_v1"));
    }

    public PolicyWebAuthnService.CeremonyOptions startRequest(PolicyGovernanceActor actor,
            String operationId, Intent intent) {
        requireActor(actor, ModelPolicyAuthority.TRUST_REQUEST, intent);
        validate(intent);
        var candidate = candidates.build(intent);
        requireCandidateScope(actor, candidate);
        return webAuthn.startBoundAssertion(actor, "trust-request", operationId,
                requestContract(intent, candidate.baseGeneration(), candidate.baseSha256(),
                        candidate.sha256()));
    }

    public CandidatePreview preview(PolicyGovernanceActor actor, Intent intent) {
        requireActor(actor, ModelPolicyAuthority.TRUST_REQUEST, intent);
        validate(intent);
        var candidate = candidates.build(intent);
        requireCandidateScope(actor, candidate);
        return new CandidatePreview(candidate.registry(), candidate.baseGeneration(),
                candidate.baseSha256(), candidate.generation(), candidate.sha256(),
                candidate.diff(), candidate.summary());
    }

    public PolicyTrustChangeDocument finishRequest(PolicyGovernanceActor actor,
            String operationId, Intent intent, String challengeId, String responseJson) {
        requireActor(actor, ModelPolicyAuthority.TRUST_REQUEST, intent);
        validate(intent);
        if (operationId == null || !String.valueOf(challengeId).equals("trust-request:" + operationId)) {
            throw new PolicyGovernanceConflictException("Trust request operation changed after step-up");
        }
        var candidate = candidates.build(intent);
        requireCandidateScope(actor, candidate);
        return transactions.execute(ignored -> {
            if (mongo.findById(operationId, PolicyTrustChangeDocument.class) != null) {
                throw new PolicyGovernanceConflictException("Trust change operation already committed");
            }
            var proof = webAuthn.acceptBoundAssertion(actor, "trust-request", challengeId,
                    responseJson, requestContract(intent, candidate.baseGeneration(),
                            candidate.baseSha256(), candidate.sha256()));
            String digest = GovernanceCanonicalJson.sha256(mapper, intent);
            var document = new PolicyTrustChangeDocument(operationId, intent, digest,
                    candidate.baseGeneration(), candidate.baseSha256(), candidate.sha256(),
                    candidate.affectsProduction(),
                    actor.id(), proof.credentialId(),
                    proof.assertionSha256(), proof.challengeId(), proof.acceptedAt(), "PENDING_REVIEW",
                    null, null, null, null, null);
            mongo.insert(new PolicyTrustCandidateDocument(operationId, candidate.registry(),
                    intent.action().name(), candidate.baseGeneration(), candidate.baseSha256(),
                    candidate.generation(), candidate.canonicalJson(), candidate.sha256(),
                    new String(GovernanceCanonicalJson.serialize(mapper, mapper.valueToTree(candidate.diff())),
                            java.nio.charset.StandardCharsets.UTF_8), candidate.summary(),
                    candidate.affectsProduction()));
            mongo.insert(document);
            mongo.insert(new PolicyTrustChangeAuditDocument(operationId + ":requested", operationId,
                    "REQUESTED", actor.id(), digest, proof.assertionSha256(), proof.challengeId(),
                    proof.acceptedAt()));
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
            if (decision == Decision.APPROVE) {
                mongo.insert(new PolicyTrustGenerationClaimDocument(
                        current.intent().registry() + ":" + current.intent().requestedGeneration(),
                        current.id(), current.baseRegistrySha256(), current.candidateSha256()));
            }
            var proof = webAuthn.acceptBoundAssertion(actor, "trust-review", challengeId,
                    responseJson, reviewContract(current, decision));
            var updated = mongo.findAndModify(Query.query(Criteria.where("_id").is(requestId)
                            .and("status").is("PENDING_REVIEW").and("intentSha256").is(current.intentSha256())),
                    new Update().set("status", decision == Decision.APPROVE
                                    ? "PENDING_OPERATOR_ACTION" : "REJECTED")
                            .set("reviewedBy", actor.id()).set("reviewerCredentialId", proof.credentialId())
                            .set("reviewAssertionSha256", proof.assertionSha256())
                            .set("reviewProofId", proof.challengeId())
                            .set("reviewedAt", proof.acceptedAt()),
                    FindAndModifyOptions.options().returnNew(true), PolicyTrustChangeDocument.class);
            if (updated == null) throw new PolicyGovernanceConflictException("Trust change was reviewed concurrently");
            mongo.insert(new PolicyTrustChangeAuditDocument(requestId + ":reviewed", requestId,
                    decision.name(), actor.id(), current.intentSha256(), proof.assertionSha256(),
                    proof.challengeId(), proof.acceptedAt()));
            return updated;
        });
    }

    public List<PolicyTrustChangeDocument> recent(PolicyGovernanceActor actor) {
        if (actor == null || !actor.authorities().contains(ModelPolicyAuthority.TRUST_READ)) {
            throw new AccessDeniedException("Institutional trust read mandate required");
        }
        Query query = new Query();
        if (!actor.authorities().contains(ModelPolicyAuthority.PRODUCTION)) {
            query.addCriteria(new Criteria().andOperator(
                    Criteria.where("intent.environment").ne(ModelGovernanceEnvironment.PRODUCTION),
                    Criteria.where("affectsProduction").is(false)));
        }
        return List.copyOf(mongo.find(query.with(Sort.by(Sort.Direction.DESC, "requestedAt"))
                .limit(50), PolicyTrustChangeDocument.class));
    }

    public CandidateView candidate(PolicyGovernanceActor actor, String requestId) {
        var request = mongo.findById(requestId, PolicyTrustChangeDocument.class);
        if (request == null || actor == null
                || !actor.authorities().contains(ModelPolicyAuthority.TRUST_READ)) {
            throw new AccessDeniedException("Trust candidate read mandate required");
        }
        var candidate = requireCandidate(request);
        if ((candidate.affectsProduction() || request.affectsProduction()
                || request.intent().environment() == ModelGovernanceEnvironment.PRODUCTION)
                && !actor.authorities().contains(ModelPolicyAuthority.PRODUCTION)) {
            throw new AccessDeniedException("Production trust candidate read mandate required");
        }
        return new CandidateView(candidate.id(), candidate.registry(), candidate.action(),
                candidate.baseGeneration(), candidate.baseSha256(), candidate.generation(),
                candidate.sha256(), mapper.readTree(candidate.semanticDiffJson()),
                candidate.reviewSummary(), request.status());
    }

    /** Protected export; the offline installer verifies both signatures again from this bundle. */
    public InstallationBundle installation(PolicyGovernanceActor auditor, String requestId) {
        verifyHistoricalChange(auditor, requestId);
        var request = mongo.findById(requestId, PolicyTrustChangeDocument.class);
        if (!"PENDING_OPERATOR_ACTION".equals(request.status())) {
            throw new PolicyGovernanceConflictException("Only approved trust candidates may be exported");
        }
        var candidate = requireCandidate(request);
        return new InstallationBundle(request, candidate,
                mongo.findById(request.requestProofId(), GovernanceBoundWebAuthnProof.class),
                mongo.findById(request.reviewProofId(), GovernanceBoundWebAuthnProof.class),
                mongo.findById(requestId + ":requested", PolicyTrustChangeAuditDocument.class),
                mongo.findById(requestId + ":reviewed", PolicyTrustChangeAuditDocument.class));
    }

    public List<PolicyTrustChangeAuditDocument> audit(PolicyGovernanceActor actor, String requestId) {
        PolicyTrustChangeDocument current = mongo.findById(requestId, PolicyTrustChangeDocument.class);
        if (current == null || actor == null || !actor.authorities().contains(ModelPolicyAuthority.TRUST_READ)
                || (current.affectsProduction()
                    || current.intent().environment() == ModelGovernanceEnvironment.PRODUCTION)
                    && !actor.authorities().contains(ModelPolicyAuthority.PRODUCTION)) {
            throw new AccessDeniedException("Trust change audit unavailable in this scope");
        }
        return List.copyOf(mongo.find(Query.query(Criteria.where("requestId").is(requestId))
                .with(Sort.by(Sort.Direction.ASC, "occurredAt")).limit(MAX_AUDIT_ENTRIES),
                PolicyTrustChangeAuditDocument.class));
    }

    /** Audit-only verification of retained signatures, independent of currently deployed trust registries. */
    public void verifyHistoricalChange(PolicyGovernanceActor auditor, String requestId) {
        PolicyTrustChangeDocument request = mongo.findById(requestId, PolicyTrustChangeDocument.class);
        if (auditor == null || !auditor.authorities().contains(ModelPolicyAuthority.AUDIT_READ)
                || request != null && (request.affectsProduction()
                    || request.intent().environment() == ModelGovernanceEnvironment.PRODUCTION)
                    && !auditor.authorities().contains(ModelPolicyAuthority.PRODUCTION)) {
            throw new AccessDeniedException("Dedicated scoped policy audit mandate required");
        }
        if (request == null || !request.intentSha256().equals(GovernanceCanonicalJson.sha256(mapper,
                request.intent()))) {
            throw new SecurityException("Historical trust request is unavailable or changed");
        }
        var candidate = requireCandidate(request);
        if (candidate.affectsProduction()
                && !auditor.authorities().contains(ModelPolicyAuthority.PRODUCTION)) {
            throw new AccessDeniedException("Production trust candidate audit mandate required");
        }
        verifyHistoricalReceipt(request, "requested", "REQUESTED", request.requestProofId(),
                request.requestedBy(), request.requesterCredentialId(), request.requestAssertionSha256(),
                request.requestedAt(), requestContract(request.intent(), request.baseGeneration(),
                        request.baseRegistrySha256(), request.candidateSha256()));
        if (!"PENDING_REVIEW".equals(request.status())) {
            Decision decision = "PENDING_OPERATOR_ACTION".equals(request.status()) ? Decision.APPROVE
                    : "REJECTED".equals(request.status()) ? Decision.REJECT : null;
            if (decision == null || request.reviewProofId() == null) {
                throw new SecurityException("Historical trust decision is inconsistent");
            }
            verifyHistoricalReceipt(request, "reviewed", decision.name(), request.reviewProofId(),
                    request.reviewedBy(), request.reviewerCredentialId(), request.reviewAssertionSha256(),
                    request.reviewedAt(), reviewContract(request, decision));
        }
    }

    private void verifyHistoricalReceipt(PolicyTrustChangeDocument request, String suffix, String action,
            String proofId, String actorId, String credentialId, String assertionSha256,
            Instant acceptedAt, Map<String, Object> contract) {
        GovernanceBoundWebAuthnProof proof = mongo.findById(proofId, GovernanceBoundWebAuthnProof.class);
        PolicyTrustChangeAuditDocument audit = mongo.findById(request.id() + ":" + suffix,
                PolicyTrustChangeAuditDocument.class);
        if (proof == null || audit == null || !proof.id().equals(proofId)
                || !proof.actorId().equals(actorId) || !proof.credentialId().equals(credentialId)
                || !proof.assertionSha256().equals(assertionSha256)
                || !proof.acceptedAt().equals(acceptedAt)
                || !request.id().equals(audit.requestId()) || !action.equals(audit.action())
                || !actorId.equals(audit.actorId())
                || !request.intentSha256().equals(audit.intentSha256())
                || !assertionSha256.equals(audit.assertionSha256())
                || !proofId.equals(audit.proofId()) || !acceptedAt.equals(audit.occurredAt())) {
            throw new SecurityException("Historical trust receipt and proof differ");
        }
        historicalVerifier.verifyHistorical(proof, contract);
    }

    public DeploymentView deployment(PolicyGovernanceActor actor, String requestId) {
        PolicyTrustChangeDocument request = mongo.findById(requestId, PolicyTrustChangeDocument.class);
        if (request == null || actor == null || !actor.authorities().contains(ModelPolicyAuthority.TRUST_READ)
                || (request.affectsProduction()
                    || request.intent().environment() == ModelGovernanceEnvironment.PRODUCTION)
                    && !actor.authorities().contains(ModelPolicyAuthority.PRODUCTION)) {
            throw new AccessDeniedException("Trust deployment state unavailable in this scope");
        }
        var current = registries.snapshot(request.intent().registry());
        Criteria observationsFor = Criteria.where("registry").is(request.intent().registry());
        if (membership.complete()) observationsFor.and("deploymentId").is(membership.deploymentId());
        List<PolicyTrustInstanceObservation> queried = mongo.find(Query.query(observationsFor)
                        .with(Sort.by(Sort.Direction.DESC, "observedAt"))
                .limit(MAX_DEPLOYMENT_OBSERVATIONS + 1), PolicyTrustInstanceObservation.class);
        boolean observationsTruncated = queried.size() > MAX_DEPLOYMENT_OBSERVATIONS;
        List<PolicyTrustInstanceObservation> all = queried.stream()
                .limit(MAX_DEPLOYMENT_OBSERVATIONS).toList();
        var assessment = PolicyTrustFleetConsistency.assess(membership, all,
                request.intent().requestedGeneration(), request.candidateSha256(),
                current.rollbackFloor(), clock.instant(), observationsTruncated);
        boolean approvedForOperatorAction = "PENDING_OPERATOR_ACTION".equals(request.status());
        String state = "PENDING_REVIEW".equals(request.status()) ? "PENDING_INDEPENDENT_REVIEW"
                : "REJECTED".equals(request.status()) ? "REJECTED"
                : approvedForOperatorAction ? assessment.rolloutState() : "UNKNOWN";
        boolean apiFileMatchesCandidate = "READY".equals(current.status())
                && current.generation() != null
                && current.generation() == request.intent().requestedGeneration()
                && request.candidateSha256().equals(current.digestSha256());
        String mismatchReason = assessment.mismatchReason();
        if ("OBSERVED_CONSISTENT".equals(state) && !apiFileMatchesCandidate) {
            state = "DEPLOYMENT_FAILED";
            mismatchReason = "READY".equals(current.status())
                    ? "API_REGISTRY_FILE_DIFFERS_FROM_APPROVED_CANDIDATE"
                    : "API_REGISTRY_FILE_UNAVAILABLE";
        }
        boolean observedConsistent = approvedForOperatorAction && "OBSERVED_CONSISTENT".equals(state);
        return new DeploymentView(request.id(), request.status(), request.intent().registry(),
                request.intent().requestedGeneration(), request.candidateSha256(),
                current.rollbackFloor(), state,
                observedConsistent ? request.intent().requestedGeneration() : null,
                observedConsistent ? request.candidateSha256() : null,
                observationsTruncated, assessment.observations(), membership.deploymentId(),
                membership.complete() ? "DECLARED_STATIC_MEMBERSHIP" : "UNKNOWN",
                "UNAUTHENTICATED_RUNTIME_HEARTBEAT", current.generation(), current.digestSha256(),
                assessment.expected(), assessment.missing(), assessment.stale(), assessment.mismatched(),
                mismatchReason, false);
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
        var candidate = requireCandidate(current);
        var live = candidates.build(current.intent());
        if (live.baseGeneration() != current.baseGeneration()
                || !live.baseSha256().equals(current.baseRegistrySha256())
                || !live.sha256().equals(candidate.sha256())
                || !live.canonicalJson().equals(candidate.canonicalJson())
                || !live.summary().equals(candidate.reviewSummary())
                || live.affectsProduction() != candidate.affectsProduction()
                || !new String(GovernanceCanonicalJson.serialize(mapper, mapper.valueToTree(live.diff())),
                        java.nio.charset.StandardCharsets.UTF_8).equals(candidate.semanticDiffJson())) {
            throw new PolicyGovernanceConflictException("Trust registry changed during independent review");
        }
        return current;
    }

    private PolicyTrustCandidateDocument requireCandidate(PolicyTrustChangeDocument request) {
        var candidate = mongo.findById(request.id(), PolicyTrustCandidateDocument.class);
        if (candidate == null || candidate.registry() != request.intent().registry()
                || !candidate.action().equals(request.intent().action().name())
                || candidate.baseGeneration() != request.baseGeneration()
                || !candidate.baseSha256().equals(request.baseRegistrySha256())
                || candidate.generation() != request.intent().requestedGeneration()
                || !candidate.sha256().equals(request.candidateSha256())
                || candidate.affectsProduction() != request.affectsProduction()
                || !candidate.sha256().equals(GovernanceCanonicalJson.sha256(
                        candidate.canonicalJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                || !candidate.semanticDiffJson().equals(new String(GovernanceCanonicalJson.serialize(mapper,
                        mapper.readTree(candidate.semanticDiffJson())),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            throw new SecurityException("Immutable exact trust candidate is unavailable or changed");
        }
        return candidate;
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
        if (intent != null && intent.registry() == PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS
                && intent.action() == Action.GRANT && intent.targetIssuer() != null
                && !actor.issuer().equals(intent.targetIssuer())) {
            throw new AccessDeniedException("Online actor mandate must use the trusted institutional issuer");
        }
    }

    private void requireIndependentReviewer(PolicyGovernanceActor actor, PolicyTrustChangeDocument current) {
        requireActor(actor, ModelPolicyAuthority.TRUST_APPROVE, current.intent());
        requireCandidateScope(actor, candidates.build(current.intent()));
        if (actor.id().equals(current.requestedBy())) {
            throw new AccessDeniedException("Trust change requires a different reviewer");
        }
    }

    private void validate(Intent intent) {
        if (intent == null || intent.registry() == null || intent.action() == null
                || intent.environment() == null || intent.intendedUse() == null
                || intent.requestedGeneration() < 2
                || intent.candidateRegistrySha256() != null && !sha(intent.candidateRegistrySha256())
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
                || !(intent.registry() == PolicyTrustRegistrySnapshotService.Registry.ISSUER_KEYS
                        ? intent.roles().equals(List.of("POLICY_ISSUER"))
                        : List.of("POLICY_AUTHOR", "POLICY_APPROVER", "POLICY_PUBLISHER",
                                "POLICY_ISSUER").containsAll(intent.roles()))) {
            throw new IllegalArgumentException("Key change exceeds supported trust policy");
        }
        if (intent.registry() != PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS) {
            if (intent.action() == Action.ADD || intent.action() == Action.ROTATE) {
                try {
                    ModelPromotionTrustedIssuerRegistry.publicKey(intent.publicKeySpkiBase64(),
                            intent.publicKeySha256());
                } catch (Exception invalid) {
                    throw new IllegalArgumentException("New public key fingerprint is invalid", invalid);
                }
            } else if (intent.publicKeySpkiBase64() != null) {
                throw new IllegalArgumentException("Retirement or revocation cannot supply a new public key");
            }
        }
    }

    private boolean sha(String digest) {
        return digest != null && digest.matches("^[0-9a-f]{64}$");
    }

    private Map<String, Object> requestContract(Intent intent, long baseGeneration,
            String baseRegistrySha256, String candidateSha256) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("domain", REQUEST_DOMAIN);
        contract.put("intentSha256", GovernanceCanonicalJson.sha256(mapper, intent));
        contract.put("registry", intent.registry().name());
        contract.put("baseGeneration", baseGeneration);
        contract.put("baseRegistrySha256", baseRegistrySha256);
        contract.put("requestedGeneration", intent.requestedGeneration());
        contract.put("candidateRegistrySha256", candidateSha256);
        return contract;
    }

    private Map<String, Object> reviewContract(PolicyTrustChangeDocument current, Decision decision) {
        return Map.of("domain", REVIEW_DOMAIN, "requestId", current.id(),
                "intentSha256", current.intentSha256(), "baseGeneration", current.baseGeneration(),
                "baseRegistrySha256", current.baseRegistrySha256(),
                "candidateRegistrySha256", current.candidateSha256(),
                "requestedGeneration", current.intent().requestedGeneration(), "decision", decision.name());
    }

    public enum Action { GRANT, ADD, ROTATE, RETIRE, REVOKE }
    public enum Decision { APPROVE, REJECT }
    public record DeploymentView(String requestId, String requestStatus,
            PolicyTrustRegistrySnapshotService.Registry registry, long requestedGeneration,
            String candidateRegistrySha256, long rollbackFloor, String rolloutState,
            Long consistentObservedGeneration, String consistentObservedRegistrySha256,
            boolean observationSetTruncated,
            List<PolicyTrustFleetConsistency.ObservationView> acknowledgements,
            String deploymentId, String membershipEvidence, String observationEvidence,
            Long configuredFileGeneration, String configuredFileDigestSha256,
            List<PolicyTrustFleetConsistency.Member> expectedInstances,
            List<PolicyTrustFleetConsistency.Member> missingInstances,
            List<PolicyTrustFleetConsistency.Member> staleInstances,
            List<PolicyTrustFleetConsistency.Member> mismatchedInstances,
            String mismatchReason, boolean activationConfirmed) { }
    public record Intent(PolicyTrustRegistrySnapshotService.Registry registry, Action action,
            String targetIdentity, String keyId, String publicKeySha256,
            String publicKeySpkiBase64, List<String> roles,
            ModelGovernanceEnvironment environment, ModelPromotionIntendedUse intendedUse,
            long requestedGeneration, String candidateRegistrySha256, String caseReference,
            String targetIssuer, String targetSubject, String displayName,
            String validFrom, String validUntil, String replacesKeyId, String retireAt) {
        public Intent(PolicyTrustRegistrySnapshotService.Registry registry, Action action,
                String targetIdentity, String keyId, String publicKeySha256,
                String publicKeySpkiBase64, List<String> roles,
                ModelGovernanceEnvironment environment, ModelPromotionIntendedUse intendedUse,
                long requestedGeneration, String candidateRegistrySha256, String caseReference) {
            this(registry, action, targetIdentity, keyId, publicKeySha256, publicKeySpkiBase64,
                    roles, environment, intendedUse, requestedGeneration, candidateRegistrySha256,
                    caseReference, null, null, null, null, null, null, null);
        }
    }
    public record CandidatePreview(PolicyTrustRegistrySnapshotService.Registry registry,
            long baseGeneration, String baseSha256, long candidateGeneration,
            String candidateSha256, PolicyTrustCandidateBuilder.Diff diff, String reviewSummary) { }
    public record CandidateView(String requestId, PolicyTrustRegistrySnapshotService.Registry registry,
            String action, long baseGeneration, String baseSha256, long candidateGeneration,
            String candidateSha256, tools.jackson.databind.JsonNode diff, String reviewSummary,
            String requestStatus) { }
    public record InstallationBundle(PolicyTrustChangeDocument request,
            PolicyTrustCandidateDocument candidate, GovernanceBoundWebAuthnProof requestProof,
            GovernanceBoundWebAuthnProof reviewProof, PolicyTrustChangeAuditDocument requestAudit,
            PolicyTrustChangeAuditDocument reviewAudit) { }

    private void requireCandidateScope(PolicyGovernanceActor actor,
            PolicyTrustCandidateBuilder.Candidate candidate) {
        if (candidate.affectsProduction() && !actor.authorities().contains(ModelPolicyAuthority.PRODUCTION)) {
            throw new AccessDeniedException("Production mandate required for every affected trust entry");
        }
    }
}
