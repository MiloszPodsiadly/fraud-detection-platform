package com.frauddetection.alert.governance.modelpromotion;

import com.mongodb.MongoException;
import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.frauddetection.alert.governance.audit.GovernanceEventCommitment;
import tools.jackson.databind.ObjectMapper;

import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceConflictException;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceService;
import com.frauddetection.alert.governance.modelpolicy.PolicyProposalDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyPublicationJobDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyPublicationAttemptDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyPublicationRecoveryEventDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyPublicationReconciliationEventDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceHistoryDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceState;
import com.frauddetection.alert.governance.modelpolicy.PolicyPublicationLimits;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Queues one bounded worker retry; this process never handles the issuer signing key. */
@Service
public class PolicyPublicationRecoveryService {
    private final MongoTemplate mongo;
    private final TransactionTemplate transactions;
    private final PolicyGovernanceService governance;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final CommittedPolicyPublicationClassifier committed;

    @Autowired
    public PolicyPublicationRecoveryService(MongoTemplate mongo, MongoTransactionManager manager,
            PolicyGovernanceService governance, ObjectMapper mapper,
            ModelPromotionPolicyAuthorityVerifier signatures,
            ModelPromotionPolicyApprovalEvidenceVerifier approvals) {
        this(mongo, new TransactionTemplate(manager), governance, Clock.systemUTC(), mapper,
                new CommittedPolicyPublicationClassifier(mongo, mapper,
                        approvals instanceof MongoModelPromotionPolicyApprovalEvidenceVerifier persisted
                                ? persisted.onlineVerifier() : null,
                        signatures instanceof Ed25519ModelPromotionPolicyAuthorityVerifier ed25519
                                ? ed25519 : null));
    }

    PolicyPublicationRecoveryService(MongoTemplate mongo, TransactionTemplate transactions,
            PolicyGovernanceService governance, Clock clock) {
        this(mongo, transactions, governance, clock, new ObjectMapper());
    }

    private PolicyPublicationRecoveryService(MongoTemplate mongo, TransactionTemplate transactions,
            PolicyGovernanceService governance, Clock clock, ObjectMapper mapper) {
        this(mongo, transactions, governance, clock, mapper,
                new CommittedPolicyPublicationClassifier(mongo, mapper, null, null));
    }

    PolicyPublicationRecoveryService(MongoTemplate mongo, TransactionTemplate transactions,
            PolicyGovernanceService governance, Clock clock,
            CommittedPolicyPublicationClassifier committed) {
        this(mongo, transactions, governance, clock, new ObjectMapper(), committed);
    }

    private PolicyPublicationRecoveryService(MongoTemplate mongo, TransactionTemplate transactions,
            PolicyGovernanceService governance, Clock clock, ObjectMapper mapper,
            CommittedPolicyPublicationClassifier committed) {
        this.mongo = mongo;
        this.transactions = transactions;
        this.governance = governance;
        this.clock = clock;
        this.mapper = mapper;
        this.committed = committed;
    }

    public Diagnostics inspect(PolicyGovernanceActor actor, String proposalId) {
        PolicyPublicationJobDocument job = requireJob(actor, proposalId);
        List<PolicyPublicationRecoveryEventDocument> events = mongo.find(Query.query(Criteria.where(
                "publicationRequestId").is(job.id())).with(Sort.by(Sort.Direction.ASC, "recoveryOrdinal"))
                .limit(PolicyPublicationLimits.MAX_RECOVERIES),
                PolicyPublicationRecoveryEventDocument.class);
        List<PolicyPublicationAttemptDocument> attempts = mongo.find(Query.query(Criteria.where(
                "publicationRequestId").is(job.id())).with(Sort.by(Sort.Direction.ASC, "attempt"))
                .limit(PolicyPublicationLimits.MAX_TOTAL_ATTEMPTS),
                PolicyPublicationAttemptDocument.class);
        List<PolicyPublicationReconciliationEventDocument> reconciliations = mongo.find(Query.query(Criteria.where(
                "publicationRequestId").is(job.id())).limit(2),
                PolicyPublicationReconciliationEventDocument.class);
        var classification = committed.classify(job);
        var proposal = governance.get(actor, proposalId);
        return new Diagnostics(job.id(), job.draftSha256(), job.state(), job.attempts(),
                job.recoveryCount(), job.reconciliationCount(), proposal.status(),
                job.failureCode(), job.leaseUntil(), classification.kind(), classification.diagnostic(),
                classification.exact(), classification.kind() == CommittedPolicyPublicationClassifier.Kind.NO_COMMIT_PROVEN
                        && retrySafe(job), reconciliationSafe(job, classification),
                classification.receipt(), List.copyOf(attempts), List.copyOf(events),
                List.copyOf(reconciliations));
    }

    public Diagnostics request(PolicyGovernanceActor actor, String proposalId, String operationId) {
        if (operationId == null || !operationId.matches("^[A-Za-z0-9._:-]{1,128}$")) {
            throw new IllegalArgumentException("Invalid recovery operation ID");
        }
        PolicyPublicationJobDocument initial = requireJob(actor, proposalId);
        requireIndependentRecoveryActor(actor, initial);
        for (int retry = 0; retry < 8; retry++) {
            try {
                boolean reconcileExisting = Boolean.TRUE.equals(transactions.execute(ignored -> {
                    PolicyPublicationRecoveryEventDocument replay = mongo.findById(operationId,
                            PolicyPublicationRecoveryEventDocument.class);
                    if (replay != null) {
                        if (!sameRecoveryIntent(replay, initial, actor, proposalId)) {
                            throw new PolicyGovernanceConflictException(
                                    "Recovery operation ID already belongs to another request");
                        }
                        return false;
                    }
                    var reconcileReplay = mongo.findById(operationId,
                            PolicyPublicationReconciliationEventDocument.class);
                    if (reconcileReplay != null) return true;
                    PolicyPublicationJobDocument job = requireJob(actor, proposalId);
                    requireIndependentRecoveryActor(actor, job);
                    var classification = committed.classify(job);
                    if (classification.exact()) return true;
                    if (classification.kind() != CommittedPolicyPublicationClassifier.Kind.NO_COMMIT_PROVEN)
                        throw new PolicyGovernanceConflictException(
                                "Publication outcome is not proven safe for another signing attempt: "
                                        + classification.kind());
                    if (!retrySafe(job)) {
                        throw new PolicyGovernanceConflictException(
                                "Publication cannot be retried by operator recovery");
                    }
                    Instant now = clock.instant();
                    Query exact = Query.query(Criteria.where("_id").is(job.id())
                            .and("state").is(PolicyPublicationJobDocument.State.BLOCKED)
                            .and("failureCode").is("ATTEMPTS_EXHAUSTED")
                            .and("attempts").is(job.attempts())
                            .and("recoveryCount").is(job.recoveryCount())
                            .and("draftSha256").is(job.draftSha256()));
                    var changed = mongo.findAndModify(exact, new Update()
                            .set("state", PolicyPublicationJobDocument.State.RETRYABLE)
                            .set("failureCode", "RECOVERY_REQUESTED")
                            .set("lastRecoveryAt", now).inc("recoveryCount", 1),
                            FindAndModifyOptions.options().returnNew(true), PolicyPublicationJobDocument.class);
                    if (changed == null) {
                        throw new PolicyGovernanceConflictException(
                                "Publication recovery raced with another operator");
                    }
                    var event = new PolicyPublicationRecoveryEventDocument(operationId, job.id(), job.proposalId(),
                            actor.id(), actor.issuer(), actor.subject(), job.draftSha256(), job.attempts(),
                            changed.recoveryCount(), job.failureCode(), now);
                    mongo.insert(event);
                    GovernanceEventCommitment.record(mongo, GovernanceEventCommitment.Kind.PUBLICATION_RECOVERY,
                            event.operationId(), event, job.draft().governanceScope().institutionalScope(),
                            actor.id(), job.proposalId(), operationId, job.draftSha256(), changed.id(), now);
                    return false;
                }));
                if (reconcileExisting) return reconcileCommitted(actor, proposalId, operationId);
                return inspect(actor, proposalId);
            } catch (RuntimeException failure) {
                // A concurrent identical request may have committed while this transaction lost the race.
                PolicyPublicationRecoveryEventDocument committed = mongo.findById(operationId,
                        PolicyPublicationRecoveryEventDocument.class);
                if (committed != null) {
                    if (!sameRecoveryIntent(committed, initial, actor, proposalId)) {
                        throw new PolicyGovernanceConflictException(
                                "Recovery operation ID already belongs to another request");
                    }
                    return inspect(actor, proposalId);
                }
                if (retry == 7 || !transientTransactionConflict(failure)) throw failure;
            }
        }
        throw new IllegalStateException("Publication recovery exhausted transaction conflict retries");
    }

    /** Completes only a cryptographically verified existing commit; it never grants a signing attempt. */
    public Diagnostics reconcileCommitted(PolicyGovernanceActor actor, String proposalId, String operationId) {
        if (operationId == null || !operationId.matches("^[A-Za-z0-9._:-]{1,128}$"))
            throw new IllegalArgumentException("Invalid reconciliation operation ID");
        PolicyPublicationJobDocument initial = requireJob(actor, proposalId);
        requireIndependentRecoveryActor(actor, initial);
        for (int retry = 0; retry < 8; retry++) {
            try {
                transactions.executeWithoutResult(ignored -> {
                    var replay = mongo.findById(operationId,
                            PolicyPublicationReconciliationEventDocument.class);
                    if (replay != null) {
                        if (!sameReconciliationIntent(replay, initial, actor, proposalId))
                            throw new PolicyGovernanceConflictException(
                                    "Reconciliation operation ID belongs to another intent");
                        var verified = committed.classify(requireJob(actor, proposalId));
                        if (!verified.exact() || !replay.policyCanonicalSha256()
                                .equals(verified.receipt().canonicalSha256())
                                || !replay.publishedAt().equals(verified.receipt().publishedAt()))
                            throw new PolicyGovernanceConflictException(
                                    "Reconciliation receipt cannot be verified");
                        return;
                    }
                    var job = requireJob(actor, proposalId);
                    requireIndependentRecoveryActor(actor, job);
                    var classification = committed.classify(job);
                    if (!classification.exact() || !reconciliationSafe(job, classification))
                        throw new PolicyGovernanceConflictException(
                                "Exact committed publication cannot be reconciled: "
                                        + classification.kind());
                    Instant now = clock.instant();
                    var stale = new Criteria().orOperator(
                            Criteria.where("state").is(PolicyPublicationJobDocument.State.BLOCKED),
                            new Criteria().andOperator(
                                    Criteria.where("state").is(PolicyPublicationJobDocument.State.CLAIMED),
                                    Criteria.where("leaseUntil").lt(now)),
                            Criteria.where("state").is(PolicyPublicationJobDocument.State.RETRYABLE));
                    var exact = Query.query(new Criteria().andOperator(
                            Criteria.where("_id").is(job.id()), stale,
                            Criteria.where("attempts").is(job.attempts()),
                            Criteria.where("publicationFence").is(job.publicationFence()),
                            Criteria.where("reconciliationCount").is(job.reconciliationCount()),
                            Criteria.where("recoveryCount").is(job.recoveryCount()),
                            Criteria.where("draftSha256").is(job.draftSha256())));
                    var updated = mongo.findAndModify(exact, new Update()
                            .set("state", PolicyPublicationJobDocument.State.PUBLISHED)
                            .set("publishedPolicySha256", classification.receipt().canonicalSha256())
                            .set("publishedAt", classification.receipt().publishedAt())
                            .unset("leaseOwner").unset("leaseUntil").unset("failureCode"),
                            FindAndModifyOptions.options().returnNew(true), PolicyPublicationJobDocument.class);
                    if (updated == null)
                        throw new PolicyGovernanceConflictException("Publication acknowledgement raced with worker");
                    mongo.updateFirst(Query.query(Criteria.where("_id").is(job.id() + ":" + job.attempts())
                                    .and("publicationRequestId").is(job.id())),
                            new Update().set("outcome", "PUBLISHED").set("endedAt", now),
                            PolicyPublicationAttemptDocument.class);
                    var proposal = mongo.findAndModify(Query.query(Criteria.where("_id").is(job.proposalId())
                                    .and("status").is(PolicyGovernanceState.PUBLICATION_PENDING)
                                    .and("version").is(job.proposalVersion())
                                    .and("publicationRequestId").is(job.id())
                                    .and("draftSha256").is(job.draftSha256())),
                            new Update().set("status", PolicyGovernanceState.PUBLISHED)
                                    .inc("version", 1).set("updatedAt", now),
                            FindAndModifyOptions.options().returnNew(true), PolicyProposalDocument.class);
                    if (proposal == null)
                        throw new PolicyGovernanceConflictException("Publication proposal changed during reconciliation");
                    var history = new PolicyGovernanceHistoryDocument(UUID.randomUUID().toString(), proposal.id(),
                            proposal.version(), "PUBLISH_POLICY", PolicyGovernanceState.PUBLICATION_PENDING,
                            PolicyGovernanceState.PUBLISHED, job.draft().publisherIdentity(),
                            "publish:" + job.id(), GovernanceCanonicalJson.sha256(mapper,
                                    Map.of("jobId", job.id(), "policySha256",
                                            classification.receipt().canonicalSha256())),
                            job.draftSha256(), job.approvalEvidenceReference(), now, null);
                    mongo.insert(history);
                    GovernanceEventCommitment.record(mongo, GovernanceEventCommitment.Kind.POLICY_PUBLICATION,
                            history.id(), history, job.draft().governanceScope().institutionalScope(),
                            job.draft().publisherIdentity(), proposal.id(), "publish:" + job.id(),
                            classification.receipt().canonicalSha256(),
                            classification.receipt().policyId() + ":" + classification.receipt().revision(), now);
                    var reconciliation = new PolicyPublicationReconciliationEventDocument(operationId, job.id(),
                            proposal.id(), actor.id(), actor.issuer(), actor.subject(), job.draftSha256(),
                            classification.receipt().canonicalSha256(), classification.receipt().publishedAt(), now);
                    mongo.insert(reconciliation);
                    GovernanceEventCommitment.record(mongo,
                            GovernanceEventCommitment.Kind.PUBLICATION_RECONCILIATION,
                            reconciliation.operationId(), reconciliation,
                            job.draft().governanceScope().institutionalScope(), actor.id(), proposal.id(),
                            operationId, classification.receipt().canonicalSha256(), job.id(), now);
                });
                return inspect(actor, proposalId);
            } catch (RuntimeException failure) {
                var receipt = mongo.findById(operationId, PolicyPublicationReconciliationEventDocument.class);
                if (receipt != null) {
                    if (!sameReconciliationIntent(receipt, initial, actor, proposalId))
                        throw new PolicyGovernanceConflictException(
                                "Reconciliation operation ID belongs to another intent");
                    var classification = committed.classify(requireJob(actor, proposalId));
                    if (!classification.exact()
                            || !receipt.policyCanonicalSha256().equals(classification.receipt().canonicalSha256()))
                        throw new PolicyGovernanceConflictException(
                                "Reconciliation receipt cannot be verified");
                    return inspect(actor, proposalId);
                }
                if (retry == 7 || !transientTransactionConflict(failure)) throw failure;
            }
        }
        throw new IllegalStateException("Reconciliation exhausted transaction conflict retries");
    }

    private boolean sameReconciliationIntent(PolicyPublicationReconciliationEventDocument event,
            PolicyPublicationJobDocument job, PolicyGovernanceActor actor, String proposalId) {
        return event.publicationRequestId().equals(job.id()) && event.proposalId().equals(proposalId)
                && event.actorId().equals(actor.id()) && event.actorIssuer().equals(actor.issuer())
                && event.actorSubject().equals(actor.subject())
                && event.draftSha256().equals(job.draftSha256());
    }

    private boolean reconciliationSafe(PolicyPublicationJobDocument job,
            CommittedPolicyPublicationClassifier.Result classification) {
        return classification.exact() && (
                job.state() == PolicyPublicationJobDocument.State.BLOCKED
                    && !"IMMUTABLE_CONFLICT".equals(job.failureCode())
                || job.state() == PolicyPublicationJobDocument.State.RETRYABLE
                || job.state() == PolicyPublicationJobDocument.State.CLAIMED
                    && job.leaseUntil() != null && job.leaseUntil().isBefore(clock.instant()));
    }

    private boolean transientTransactionConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoException mongoFailure
                    && mongoFailure.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) return true;
        }
        return false;
    }

    private boolean sameRecoveryIntent(PolicyPublicationRecoveryEventDocument event,
            PolicyPublicationJobDocument job, PolicyGovernanceActor actor, String proposalId) {
        return event.publicationRequestId().equals(job.id())
                && event.proposalId().equals(proposalId)
                && event.actorId().equals(actor.id())
                && event.actorIssuer().equals(actor.issuer())
                && event.actorSubject().equals(actor.subject())
                && event.draftSha256().equals(job.draftSha256());
    }

    private boolean retrySafe(PolicyPublicationJobDocument job) {
        return job.state() == PolicyPublicationJobDocument.State.BLOCKED
                && "ATTEMPTS_EXHAUSTED".equals(job.failureCode())
                && job.recoveryCount() >= 0
                && job.recoveryCount() < PolicyPublicationLimits.MAX_RECOVERIES
                && job.attempts() == PolicyPublicationLimits.authorizedAttempts(job.recoveryCount());
    }

    private PolicyPublicationJobDocument requireJob(PolicyGovernanceActor actor, String proposalId) {
        if (actor == null || !actor.authorities().contains(ModelPolicyAuthority.PUBLICATION_RECOVER)) {
            throw new AccessDeniedException("Publication recovery mandate required");
        }
        PolicyProposalDocument proposal = governance.get(actor, proposalId);
        if (proposal.publicationRequestId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Publication job not found");
        }
        PolicyPublicationJobDocument job = mongo.findById(proposal.publicationRequestId(),
                PolicyPublicationJobDocument.class);
        if (job == null || !proposal.id().equals(job.proposalId())
                || !proposal.draftSha256().equals(job.draftSha256())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Publication job linkage unavailable");
        }
        return job;
    }

    private void requireIndependentRecoveryActor(PolicyGovernanceActor actor, PolicyPublicationJobDocument job) {
        if (actor.id().equals(job.requestingActorId()) || actor.id().equals(job.authorActorId())
                || actor.id().equals(job.approverActorId())) {
            throw new AccessDeniedException("Independent recovery operator required");
        }
        if (job.draft().governanceScope().environment() == ModelGovernanceEnvironment.PRODUCTION
                && (!actor.authorities().contains(ModelPolicyAuthority.PRODUCTION)
                    || !actor.authorities().contains(ModelPolicyAuthority.APPROVE))) {
            throw new AccessDeniedException("Production recovery requires scope and independent approval mandate");
        }
    }

    public record Diagnostics(String publicationRequestId, String draftSha256,
            PolicyPublicationJobDocument.State state, int attempts, int recoveryCount,
            int reconciliationCount, PolicyGovernanceState proposalState, String failureCode,
            Instant leaseUntil, CommittedPolicyPublicationClassifier.Kind classification,
            String classificationDiagnostic, boolean alreadyCommitted, boolean retrySafe,
            boolean reconcileSafe, CommittedPolicyPublicationClassifier.VerifiedReceipt publicationReceipt,
            List<PolicyPublicationAttemptDocument> attemptHistory,
            List<PolicyPublicationRecoveryEventDocument> recoveryHistory,
            List<PolicyPublicationReconciliationEventDocument> reconciliationHistory) { }
}
