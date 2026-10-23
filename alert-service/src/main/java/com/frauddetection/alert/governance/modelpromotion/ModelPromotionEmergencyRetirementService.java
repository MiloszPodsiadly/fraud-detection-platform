package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import jakarta.annotation.PostConstruct;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OIDC-only exceptional route: two independent dedicated mandates, exact intent, short one-use grant. */
@Service
public class ModelPromotionEmergencyRetirementService {
    private static final int GRANT_SECONDS = 300;
    private final MongoTemplate mongo;
    private final TransactionTemplate transactions;
    private final MongoModelPromotionStore store;
    private final ModelPromotionWorkflowService workflow;
    private final ModelPromotionAuthorization authorization;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ModelPromotionEmergencyRetirementService(MongoTemplate mongo, MongoTransactionManager manager,
            MongoModelPromotionStore store, ModelPromotionWorkflowService workflow,
            ModelPromotionAuthorization authorization, ObjectMapper mapper) {
        this(mongo, new TransactionTemplate(manager), store, workflow, authorization, mapper, Clock.systemUTC());
    }

    ModelPromotionEmergencyRetirementService(MongoTemplate mongo, TransactionTemplate transactions,
            MongoModelPromotionStore store, ModelPromotionWorkflowService workflow,
            ModelPromotionAuthorization authorization, ObjectMapper mapper, Clock clock) {
        this.mongo = mongo;
        this.transactions = transactions;
        this.store = store;
        this.workflow = workflow;
        this.authorization = authorization;
        this.mapper = mapper;
        this.clock = clock;
    }

    @PostConstruct
    void ensureIndexes() {
        mongo.indexOps(ModelPromotionEmergencyAuthorizationDocument.class).ensureIndex(new Index()
                .on("reviewOperationId", Sort.Direction.ASC).unique()
                .named("model_promotion_emergency_review_operation_unique_v1"));
        mongo.indexOps(ModelPromotionEmergencyAuthorizationDocument.class).ensureIndex(new Index()
                .on("proposalId", Sort.Direction.ASC).on("status", Sort.Direction.ASC)
                .named("model_promotion_emergency_proposal_status_v1"));
        mongo.indexOps(ModelPromotionEmergencyAuditDocument.class).ensureIndex(new Index()
                .on("authorizationId", Sort.Direction.ASC).on("occurredAt", Sort.Direction.ASC)
                .named("model_promotion_emergency_audit_v1"));
    }

    public Summary request(PolicyGovernanceActor actor, String proposalId, Request command) {
        requireRequester(actor);
        validate(command);
        ModelPromotionProposalDocument proposal = pendingRetirement(proposalId);
        requireReviewerFor(actor, proposal);
        ModelPromotionAggregate current = exactCurrentState(proposal);
        Instant createdAt = clock.instant();
        Instant expiresAt = createdAt.plusSeconds(GRANT_SECONDS);
        String reasonSha256 = ModelPromotionDecisionRationale.sha256(command.reason());
        var draft = new ModelPromotionEmergencyAuthorizationDocument(command.authorizationId(),
                ModelPromotionEmergencyAuthorizationDocument.VERSION, proposalId, proposal.commandSha256(),
                proposal.transition(), current.lifecycleState(), current.revision(), command.reviewOperationId(),
                command.approvalId(), actor.id(), actor.issuer(), actor.subject(), command.incidentId(),
                command.incidentEvidenceReference(), command.reason(), reasonSha256, null,
                createdAt, expiresAt, ModelPromotionEmergencyAuthorizationDocument.Status.REQUESTED,
                null, null, null, null, null, null, null, null);
        var document = new ModelPromotionEmergencyAuthorizationDocument(draft.id(), draft.version(),
                draft.proposalId(), draft.proposalSha256(), draft.transition(), draft.currentState(),
                draft.currentRevision(), draft.reviewOperationId(), draft.approvalId(),
                draft.requesterActorId(), draft.requesterIssuer(), draft.requesterSubject(),
                draft.incidentId(), draft.incidentEvidenceReference(), draft.reason(), draft.reasonSha256(),
                intentSha256(draft), draft.createdAt(), draft.expiresAt(), draft.status(),
                null, null, null, null, null, null, null, null);
        try {
            return transactions.execute(ignored -> {
                mongo.insert(document);
                audit(document, "REQUESTED", actor, createdAt);
                return summary(document);
            });
        } catch (DuplicateKeyException duplicate) {
            var existing = mongo.findById(command.authorizationId(),
                    ModelPromotionEmergencyAuthorizationDocument.class);
            if (existing == null || !existing.proposalId().equals(proposalId)
                    || !existing.proposalSha256().equals(proposal.commandSha256())
                    || !existing.transition().equals(proposal.transition())
                    || !existing.reviewOperationId().equals(command.reviewOperationId())
                    || !existing.approvalId().equals(command.approvalId())
                    || !existing.requesterActorId().equals(actor.id())
                    || !existing.incidentId().equals(command.incidentId())
                    || !existing.incidentEvidenceReference().equals(command.incidentEvidenceReference())
                    || !existing.reason().equals(command.reason())) {
                throw new ModelPromotionPersistenceConflictException("Emergency operation ID already used");
            }
            return summary(requireIntact(existing.id()));
        }
    }

    public IssuedGrant authorize(PolicyGovernanceActor actor, String authorizationId,
            AuthorizationAttestation attestation) {
        requireAuthorizer(actor);
        if (attestation == null || attestation.intentSha256() == null
                || attestation.incidentEvidenceReference() == null
                || attestation.authorizationRationale() == null
                || attestation.authorizationRationale().trim().length() < 20
                || attestation.authorizationRationale().length() > 4096) {
            throw new IllegalArgumentException("Independent incident-evidence attestation required");
        }
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = authorizationId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Instant now = clock.instant();
        return transactions.execute(ignored -> {
            var grant = requireIntact(authorizationId);
            if (grant.status() != ModelPromotionEmergencyAuthorizationDocument.Status.REQUESTED
                    || !now.isBefore(grant.expiresAt())
                    || !grant.intentSha256().equals(attestation.intentSha256())
                    || !grant.incidentEvidenceReference().equals(attestation.incidentEvidenceReference())
                    || actor.id().equals(grant.requesterActorId())) {
                throw new ModelPromotionPersistenceConflictException("Emergency request unavailable for independent authorization");
            }
            var proposal = pendingRetirement(grant.proposalId());
            requireExactProposalAndState(grant, proposal);
            if (actor.subject().equals(proposal.proposal().proposedBy())) {
                throw new AccessDeniedException("Model proposer cannot authorize emergency retirement");
            }
            var updated = mongo.findAndModify(Query.query(Criteria.where("_id").is(grant.id())
                            .and("status").is(ModelPromotionEmergencyAuthorizationDocument.Status.REQUESTED)
                            .and("expiresAt").gt(now).and("intentSha256").is(grant.intentSha256())),
                    new Update().set("status", ModelPromotionEmergencyAuthorizationDocument.Status.AUTHORIZED)
                            .set("authorizedByActorId", actor.id()).set("authorizedByIssuer", actor.issuer())
                            .set("authorizedBySubject", actor.subject())
                            .set("authorizationRationale", attestation.authorizationRationale().trim())
                            .set("authorizedAt", now).set("tokenSha256", sha256(token)),
                    FindAndModifyOptions.options().returnNew(true),
                    ModelPromotionEmergencyAuthorizationDocument.class);
            if (updated == null) throw new ModelPromotionPersistenceConflictException("Emergency request changed");
            audit(updated, "AUTHORIZED", actor, now);
            return new IssuedGrant(updated.id(), token, updated.expiresAt());
        });
    }

    public AuthorizationReviewView inspect(PolicyGovernanceActor actor, String authorizationId) {
        requireAuthorizer(actor);
        var grant = requireIntact(authorizationId);
        var transition = grant.transition();
        return new AuthorizationReviewView(grant.id(), grant.proposalId(), grant.proposalSha256(),
                transition.artifactIdentity(), transition.governanceScope(), transition.intendedUse(),
                grant.currentState(), grant.currentRevision(), transition.requestedTargetState(),
                grant.reviewOperationId(), grant.approvalId(), grant.requesterActorId(), grant.incidentId(),
                grant.incidentEvidenceReference(), grant.reason(), grant.intentSha256(),
                grant.createdAt(), grant.expiresAt(), grant.status().name());
    }

    public Summary revoke(PolicyGovernanceActor actor, String authorizationId) {
        requireAuthorizer(actor);
        Instant now = clock.instant();
        return transactions.execute(ignored -> {
            var grant = requireIntact(authorizationId);
            if (grant.status() != ModelPromotionEmergencyAuthorizationDocument.Status.REQUESTED
                    && grant.status() != ModelPromotionEmergencyAuthorizationDocument.Status.AUTHORIZED) {
                throw new ModelPromotionPersistenceConflictException("Emergency authorization is no longer revocable");
            }
            if (actor.id().equals(grant.requesterActorId())) {
                throw new AccessDeniedException("Emergency requester cannot revoke their own grant");
            }
            var updated = mongo.findAndModify(Query.query(Criteria.where("_id").is(grant.id())
                            .and("status").is(grant.status()).and("intentSha256").is(grant.intentSha256())),
                    new Update().set("status", ModelPromotionEmergencyAuthorizationDocument.Status.REVOKED)
                            .set("revokedAt", now).unset("tokenSha256"),
                    FindAndModifyOptions.options().returnNew(true),
                    ModelPromotionEmergencyAuthorizationDocument.class);
            if (updated == null) throw new ModelPromotionPersistenceConflictException("Emergency grant changed");
            audit(updated, "REVOKED", actor, now);
            return summary(updated);
        });
    }

    public ModelPromotionProposalResult retire(PolicyGovernanceActor actor, String proposalId,
            String authorizationId, String token) {
        requireRequester(actor);
        var grant = requireIntact(authorizationId);
        if (!proposalId.equals(grant.proposalId()) || !actor.id().equals(grant.requesterActorId())
                || !actor.issuer().equals(grant.requesterIssuer())
                || !actor.subject().equals(grant.requesterSubject())) {
            throw new AccessDeniedException("Emergency grant is bound to another requester or proposal");
        }
        var proposal = pendingRetirement(proposalId);
        requireReviewerFor(actor, proposal);
        requireExactProposalAndState(grant, proposal);
        var review = new ModelPromotionWorkflowService.ReviewRequest(proposalId, grant.approvalId(),
                ModelPromotionApproval.Outcome.APPROVED, grant.reviewOperationId(), grant.reason(),
                grant.reasonSha256());
        return workflow.reviewEmergencyRetirement(review, () -> consumeInTransaction(grant, actor, token));
    }

    public AuditView auditView(PolicyGovernanceActor auditor, String authorizationId) {
        if (auditor == null || !auditor.authorities().contains(ModelPolicyAuthority.AUDIT_READ)
                || !auditor.authorities().contains(AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION)) {
            throw new AccessDeniedException("Dedicated production governance audit mandate required");
        }
        var grant = requireIntact(authorizationId);
        List<ModelPromotionEmergencyAuditDocument> events = mongo.find(Query.query(Criteria.where("authorizationId")
                        .is(authorizationId)).with(Sort.by(Sort.Direction.ASC, "occurredAt")).limit(4),
                ModelPromotionEmergencyAuditDocument.class);
        if (events.stream().anyMatch(event -> !grant.id().equals(event.authorizationId())
                || !grant.proposalId().equals(event.proposalId())
                || !grant.intentSha256().equals(event.intentSha256())
                || !grant.incidentId().equals(event.incidentId()))
                || !hasEvent(events, "REQUESTED", grant.requesterActorId())
                || grant.authorizedAt() != null && !hasEvent(events, "AUTHORIZED", grant.authorizedByActorId())
                || grant.consumedAt() != null && !hasEvent(events, "CONSUMED", grant.requesterActorId())
                || grant.revokedAt() != null && events.stream().noneMatch(event -> "REVOKED".equals(event.action()))) {
            throw new SecurityException("Emergency authorization audit trail is incomplete or inconsistent");
        }
        boolean committed = grant.status() == ModelPromotionEmergencyAuthorizationDocument.Status.CONSUMED
                && store.findProposal(grant.proposalId()).filter(proposal ->
                    proposal.reviewStatus() == ModelPromotionProposalDocument.ReviewStatus.APPROVED
                        && grant.reviewOperationId().equals(proposal.reviewOperationId())
                        && proposal.transition().equals(grant.transition())
                        && grant.approvalId().equals(proposal.approval().approvalId())
                        && grant.requesterSubject().equals(proposal.approval().approvedBy())
                        && "EMERGENCY_RETIRE_MODEL_PROMOTION_PROPOSAL".equals(proposal.reviewAudit().action())
                        && proposal.transition().requestedTargetState() == ModelPromotionLifecycleState.RETIRED)
                .isPresent() && matchingRetirementDecision(grant);
        if (grant.status() == ModelPromotionEmergencyAuthorizationDocument.Status.CONSUMED && !committed) {
            throw new SecurityException("Consumed emergency grant has no matching retired decision");
        }
        return new AuditView(grant.id(), grant.proposalId(), grant.incidentId(),
                grant.incidentEvidenceReference(), grant.reason(), grant.reasonSha256(),
                grant.authorizationRationale(), grant.transition(), grant.currentState(), grant.currentRevision(),
                grant.requesterActorId(), grant.authorizedByActorId(), grant.reviewOperationId(),
                grant.intentSha256(), grant.status().name(), grant.createdAt(), grant.expiresAt(),
                grant.authorizedAt(), grant.consumedAt(), grant.revokedAt(),
                committed ? "INDEPENDENT_AUTHORIZATION_AND_RETIREMENT_COMMITTED"
                        : "NO_COMMITTED_EMERGENCY_RETIREMENT_PROOF", List.copyOf(events));
    }

    private boolean hasEvent(List<ModelPromotionEmergencyAuditDocument> events, String action, String actorId) {
        return events.stream().anyMatch(event -> action.equals(event.action())
                && actorId != null && actorId.equals(event.actorId()));
    }

    private boolean matchingRetirementDecision(ModelPromotionEmergencyAuthorizationDocument grant) {
        var transition = grant.transition();
        var decision = mongo.findById(ModelPromotionPersistenceKey.operation(
                ModelPromotionPersistenceKey.aggregate(transition.artifactIdentity(),
                        transition.governanceScope(), transition.intendedUse()), grant.reviewOperationId()),
                ModelPromotionDecisionDocument.class);
        return decision != null
                && decision.eventType() == ModelPromotionDecisionDocument.EventType.LIFECYCLE_TRANSITION_ACCEPTED
                && decision.resultingState() == ModelPromotionLifecycleState.RETIRED
                && decision.sourceState() == grant.currentState()
                && decision.resultingRevision() == grant.currentRevision() + 1
                && grant.requesterSubject().equals(decision.actorId())
                && grant.approvalId().equals(decision.approval().approvalId())
                && grant.proposalId().equals(decision.proposal().proposalId())
                && transition.artifactIdentity().equals(decision.artifactIdentity())
                && transition.governanceScope().equals(decision.governanceScope())
                && transition.intendedUse().equals(decision.intendedUse());
    }

    private void consumeInTransaction(ModelPromotionEmergencyAuthorizationDocument expected,
            PolicyGovernanceActor actor, String token) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Emergency authorization must be consumed with model retirement");
        }
        Instant now = clock.instant();
        var current = requireIntact(expected.id());
        if (current.status() != ModelPromotionEmergencyAuthorizationDocument.Status.AUTHORIZED
                || current.authorizedByActorId() == null || current.authorizedByActorId().equals(actor.id())
                || !current.requesterActorId().equals(actor.id())
                || !now.isBefore(current.expiresAt()) || current.tokenSha256() == null
                || token == null || !token.matches("^[A-Za-z0-9._:@-]{1,128}\\.[A-Za-z0-9_-]{43}$")
                || !MessageDigest.isEqual(current.tokenSha256().getBytes(StandardCharsets.US_ASCII),
                        sha256(token).getBytes(StandardCharsets.US_ASCII))) {
            throw new AccessDeniedException("Active exact emergency authorization required");
        }
        var updated = mongo.findAndModify(Query.query(Criteria.where("_id").is(current.id())
                        .and("status").is(ModelPromotionEmergencyAuthorizationDocument.Status.AUTHORIZED)
                        .and("intentSha256").is(current.intentSha256())
                        .and("tokenSha256").is(current.tokenSha256()).and("expiresAt").gt(now)),
                new Update().set("status", ModelPromotionEmergencyAuthorizationDocument.Status.CONSUMED)
                        .set("consumedAt", now),
                FindAndModifyOptions.options().returnNew(true),
                ModelPromotionEmergencyAuthorizationDocument.class);
        if (updated == null) throw new ModelPromotionPersistenceConflictException("Emergency grant already used");
        audit(updated, "CONSUMED", actor, now);
    }

    private ModelPromotionEmergencyAuthorizationDocument requireIntact(String id) {
        if (id == null || !id.matches("^[A-Za-z0-9._:@-]{1,128}$")) {
            throw new IllegalArgumentException("Valid emergency authorization ID required");
        }
        var grant = mongo.findById(id, ModelPromotionEmergencyAuthorizationDocument.class);
        if (grant == null || !ModelPromotionEmergencyAuthorizationDocument.VERSION.equals(grant.version())
                || !grant.intentSha256().equals(intentSha256(grant))
                || !grant.reasonSha256().equals(ModelPromotionDecisionRationale.sha256(grant.reason()))) {
            throw new SecurityException("Exact emergency authorization is unavailable or changed");
        }
        return grant;
    }

    private ModelPromotionProposalDocument pendingRetirement(String proposalId) {
        var proposal = store.findProposal(proposalId).orElseThrow(() ->
                new ModelPromotionPersistenceConflictException("Model retirement proposal unavailable"));
        if (proposal.reviewStatus() != ModelPromotionProposalDocument.ReviewStatus.PENDING
                || proposal.transition().requestedTargetState() != ModelPromotionLifecycleState.RETIRED
                || proposal.transition().governanceScope().environment() != ModelGovernanceEnvironment.PRODUCTION) {
            throw new ModelPromotionPersistenceConflictException("Pending production retirement required");
        }
        return proposal;
    }

    private ModelPromotionAggregate exactCurrentState(ModelPromotionProposalDocument proposal) {
        var transition = proposal.transition();
        var current = store.currentState(transition.artifactIdentity(), transition.governanceScope(),
                transition.intendedUse()).orElseThrow(() -> new ModelPromotionPersistenceConflictException(
                        "Exact model lifecycle unavailable"));
        if (current.lifecycleState() != transition.sourceState()
                || current.revision() != transition.expectedRevision()) {
            throw new ModelPromotionPersistenceConflictException("Emergency retirement revision is stale");
        }
        return current;
    }

    private void requireExactProposalAndState(ModelPromotionEmergencyAuthorizationDocument grant,
            ModelPromotionProposalDocument proposal) {
        if (!grant.proposalId().equals(proposal.proposalId())
                || !grant.proposalSha256().equals(proposal.commandSha256())
                || !grant.transition().equals(proposal.transition())
                || grant.currentRevision() != proposal.transition().expectedRevision()
                || grant.currentState() != proposal.transition().sourceState()) {
            throw new ModelPromotionPersistenceConflictException("Emergency grant targets another model or revision");
        }
        exactCurrentState(proposal);
    }

    private void requireRequester(PolicyGovernanceActor actor) {
        requireOidcIdentity(actor, AnalystAuthority.MODEL_PROMOTION_EMERGENCY_REQUEST);
        if (!actor.authorities().contains(AnalystAuthority.MODEL_PROMOTION_APPROVE)
                || !actor.authorities().contains(AnalystAuthority.MODEL_PROMOTION_RETIRE)) {
            throw new AccessDeniedException("Emergency requester also needs review and retirement mandates");
        }
    }

    private void requireReviewerFor(PolicyGovernanceActor actor, ModelPromotionProposalDocument proposal) {
        var scope = proposal.transition().governanceScope();
        if (!actor.subject().equals(authorization.requireActor(AnalystAuthority.MODEL_PROMOTION_APPROVE, scope))) {
            throw new AccessDeniedException("OIDC requester and model reviewer differ");
        }
        authorization.requireAuthority(AnalystAuthority.MODEL_PROMOTION_RETIRE, scope);
        if (actor.subject().equals(proposal.proposal().proposedBy())) {
            throw new AccessDeniedException("Model proposer cannot emergency-review own proposal");
        }
    }

    private void requireAuthorizer(PolicyGovernanceActor actor) {
        requireOidcIdentity(actor, AnalystAuthority.MODEL_PROMOTION_EMERGENCY_AUTHORIZE);
    }

    private void requireOidcIdentity(PolicyGovernanceActor actor, String mandate) {
        if (actor == null || !actor.authorities().contains(mandate)
                || !actor.authorities().contains(AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION)
                || !actor.id().equals(PolicyGovernanceActor.identityId(actor.issuer(), actor.subject()))) {
            throw new AccessDeniedException("Dedicated production emergency OIDC mandate required");
        }
    }

    private void validate(Request command) {
        if (command == null || !identifier(command.authorizationId())
                || !identifier(command.reviewOperationId()) || !identifier(command.approvalId())
                || command.incidentId() == null
                || !command.incidentId().matches("^[A-Za-z0-9._:-]{6,128}$")
                || command.incidentEvidenceReference() == null
                || !command.incidentEvidenceReference().matches("^[A-Za-z0-9._:/#-]{8,256}$")
                || command.reason() == null || command.reason().trim().length() < 20
                || command.reason().length() > 4096) {
            throw new IllegalArgumentException("Exact emergency incident evidence and review intent required");
        }
    }

    private boolean identifier(String value) {
        return value != null && value.matches("^[A-Za-z0-9._:@-]{1,128}$");
    }

    private String intentSha256(ModelPromotionEmergencyAuthorizationDocument grant) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("domain", "com.frauddetection.model-promotion.emergency-retirement.v1");
        contract.put("version", grant.version());
        contract.put("authorizationId", grant.id());
        contract.put("proposalId", grant.proposalId());
        contract.put("proposalSha256", grant.proposalSha256());
        contract.put("transition", grant.transition());
        contract.put("currentState", grant.currentState());
        contract.put("currentRevision", grant.currentRevision());
        contract.put("outcome", ModelPromotionApproval.Outcome.APPROVED);
        contract.put("reviewOperationId", grant.reviewOperationId());
        contract.put("approvalId", grant.approvalId());
        contract.put("requesterActorId", grant.requesterActorId());
        contract.put("requesterIssuer", grant.requesterIssuer());
        contract.put("requesterSubject", grant.requesterSubject());
        contract.put("incidentId", grant.incidentId());
        contract.put("incidentEvidenceReference", grant.incidentEvidenceReference());
        contract.put("reason", grant.reason());
        contract.put("reasonSha256", grant.reasonSha256());
        contract.put("createdAt", grant.createdAt());
        contract.put("expiresAt", grant.expiresAt());
        return GovernanceCanonicalJson.sha256(mapper, contract);
    }

    private String sha256(String value) {
        return ModelPromotionDecisionRationale.sha256(value);
    }

    private void audit(ModelPromotionEmergencyAuthorizationDocument grant, String action,
            PolicyGovernanceActor actor, Instant at) {
        mongo.insert(new ModelPromotionEmergencyAuditDocument(grant.id() + ":" + action.toLowerCase(),
                grant.id(), grant.proposalId(), action, actor.id(), actor.issuer(), actor.subject(),
                grant.intentSha256(), grant.incidentId(), at));
    }

    private Summary summary(ModelPromotionEmergencyAuthorizationDocument grant) {
        return new Summary(grant.id(), grant.proposalId(), grant.status().name(), grant.expiresAt());
    }

    public record Request(String authorizationId, String reviewOperationId, String approvalId,
            String incidentId, String incidentEvidenceReference, String reason) { }
    public record AuthorizationAttestation(String intentSha256, String incidentEvidenceReference,
            String authorizationRationale) { }
    public record Consume(String authorizationId, String token) { }
    public record Summary(String authorizationId, String proposalId, String status, Instant expiresAt) { }
    public record IssuedGrant(String authorizationId, String token, Instant expiresAt) { }
    public record AuthorizationReviewView(String authorizationId, String proposalId, String proposalSha256,
            com.frauddetection.alert.governance.ExactModelArtifactIdentity artifactIdentity,
            ModelGovernanceScope governanceScope, ModelPromotionIntendedUse intendedUse,
            ModelPromotionLifecycleState currentState, long currentRevision,
            ModelPromotionLifecycleState requiredOutcome, String reviewOperationId,
            String approvalId, String requesterActorId, String incidentId,
            String incidentEvidenceReference, String reason, String intentSha256,
            Instant createdAt, Instant expiresAt, String status) { }
    public record AuditView(String authorizationId, String proposalId, String incidentId,
            String incidentEvidenceReference, String reason, String reasonSha256,
            String authorizationRationale, ModelPromotionTransitionProposal transition,
            ModelPromotionLifecycleState currentState, long currentRevision,
            String requesterActorId, String authorizedByActorId, String reviewOperationId,
            String intentSha256, String status, Instant createdAt, Instant expiresAt,
            Instant authorizedAt, Instant consumedAt, Instant revokedAt,
            String proofStatus, List<ModelPromotionEmergencyAuditDocument> events) { }
}
