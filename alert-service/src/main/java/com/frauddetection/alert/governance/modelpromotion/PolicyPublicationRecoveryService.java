package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceConflictException;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceService;
import com.frauddetection.alert.governance.modelpolicy.PolicyProposalDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyPublicationJobDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyPublicationAttemptDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyPublicationRecoveryEventDocument;
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

/** Queues one bounded worker retry; this process never handles the issuer signing key. */
@Service
public class PolicyPublicationRecoveryService {
    private static final int MAX_RECOVERIES = 3;
    private static final int MAX_PUBLICATION_ATTEMPTS = 5 + MAX_RECOVERIES;
    private final MongoTemplate mongo;
    private final TransactionTemplate transactions;
    private final PolicyGovernanceService governance;
    private final Clock clock;

    @Autowired
    public PolicyPublicationRecoveryService(MongoTemplate mongo, MongoTransactionManager manager,
            PolicyGovernanceService governance) {
        this(mongo, new TransactionTemplate(manager), governance, Clock.systemUTC());
    }

    PolicyPublicationRecoveryService(MongoTemplate mongo, TransactionTemplate transactions,
            PolicyGovernanceService governance, Clock clock) {
        this.mongo = mongo;
        this.transactions = transactions;
        this.governance = governance;
        this.clock = clock;
    }

    public Diagnostics inspect(PolicyGovernanceActor actor, String proposalId) {
        PolicyPublicationJobDocument job = requireJob(actor, proposalId);
        List<PolicyPublicationRecoveryEventDocument> events = mongo.find(Query.query(Criteria.where(
                "publicationRequestId").is(job.id())).with(Sort.by(Sort.Direction.ASC, "requestedAt"))
                .limit(MAX_RECOVERIES),
                PolicyPublicationRecoveryEventDocument.class);
        List<PolicyPublicationAttemptDocument> attempts = mongo.find(Query.query(Criteria.where(
                "publicationRequestId").is(job.id())).with(Sort.by(Sort.Direction.ASC, "attempt"))
                .limit(MAX_PUBLICATION_ATTEMPTS),
                PolicyPublicationAttemptDocument.class);
        boolean committed = false;
        boolean conflict = false;
        try {
            committed = equivalentPublicationCommitted(job);
        } catch (PolicyGovernanceConflictException immutable) {
            conflict = true;
        }
        return new Diagnostics(job.id(), job.draftSha256(), job.state(), job.attempts(),
                job.recoveryCount(), conflict ? "IMMUTABLE_CONFLICT" : job.failureCode(), job.leaseUntil(),
                committed, !conflict && retrySafe(job), List.copyOf(attempts), List.copyOf(events));
    }

    public Diagnostics request(PolicyGovernanceActor actor, String proposalId, String operationId) {
        if (operationId == null || !operationId.matches("^[A-Za-z0-9._:-]{1,128}$")) {
            throw new IllegalArgumentException("Invalid recovery operation ID");
        }
        PolicyPublicationJobDocument initial = requireJob(actor, proposalId);
        requireIndependentRecoveryActor(actor, initial);
        transactions.executeWithoutResult(ignored -> {
            PolicyPublicationRecoveryEventDocument replay = mongo.findById(operationId,
                    PolicyPublicationRecoveryEventDocument.class);
            if (replay != null) {
                if (!replay.publicationRequestId().equals(initial.id()) || !replay.actorId().equals(actor.id())) {
                    throw new PolicyGovernanceConflictException("Recovery operation ID already belongs to another request");
                }
                return;
            }
            PolicyPublicationJobDocument job = requireJob(actor, proposalId);
            requireIndependentRecoveryActor(actor, job);
            if (!retrySafe(job)) {
                throw new PolicyGovernanceConflictException("Publication cannot be retried by operator recovery");
            }
            // An equivalent prior commit is reconciled by the worker before any new signing.
            // Conflicting immutable material is never routed to a new signature attempt.
            equivalentPublicationCommitted(job);
            Instant now = clock.instant();
            Query exact = Query.query(Criteria.where("_id").is(job.id())
                    .and("state").is(PolicyPublicationJobDocument.State.BLOCKED)
                    .and("failureCode").is("ATTEMPTS_EXHAUSTED")
                    .and("attempts").is(job.attempts())
                    .and("recoveryCount").in(job.recoveryCount(),
                            job.recoveryCount() == 0 ? null : job.recoveryCount())
                    .and("draftSha256").is(job.draftSha256()));
            var changed = mongo.findAndModify(exact, new Update()
                    .set("state", PolicyPublicationJobDocument.State.RETRYABLE)
                    .set("failureCode", "RECOVERY_REQUESTED")
                    .set("lastRecoveryAt", now).inc("recoveryCount", 1),
                    FindAndModifyOptions.options().returnNew(true), PolicyPublicationJobDocument.class);
            if (changed == null) throw new PolicyGovernanceConflictException("Publication recovery raced with another operator");
            mongo.insert(new PolicyPublicationRecoveryEventDocument(operationId, job.id(), job.proposalId(),
                    actor.id(), actor.issuer(), actor.subject(), job.draftSha256(), job.attempts(),
                    changed.recoveryCount(), job.failureCode(), now));
        });
        return inspect(actor, proposalId);
    }

    private boolean retrySafe(PolicyPublicationJobDocument job) {
        return job.state() == PolicyPublicationJobDocument.State.BLOCKED
                && "ATTEMPTS_EXHAUSTED".equals(job.failureCode())
                && job.recoveryCount() < MAX_RECOVERIES && job.attempts() < MAX_PUBLICATION_ATTEMPTS;
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

    private boolean equivalentPublicationCommitted(PolicyPublicationJobDocument job) {
        String key = ModelPromotionPolicyDocument.policyKey(job.draft().governanceScope(),
                job.draft().intendedUse(), job.draft().policyId(), job.draft().revision());
        ModelPromotionPolicyDocument published = mongo.findById(key, ModelPromotionPolicyDocument.class);
        if (published == null) return false;
        InternalPolicyApprovalEvidenceDocument evidence = mongo.findById(key,
                InternalPolicyApprovalEvidenceDocument.class);
        if (evidence == null || evidence.onlineApprovalProof() == null
                || !job.id().equals(evidence.onlineApprovalProof().publicationRequestId())
                || !job.draftSha256().equals(evidence.onlineApprovalProof().draftSha256())
                || !job.draft().equals(evidence.onlineApprovalProof().draft())
                || !published.policy().canonicalSha256().equals(evidence.policyCanonicalSha256())) {
            throw new PolicyGovernanceConflictException("Immutable publication conflicts with recovery request");
        }
        return true;
    }

    public record Diagnostics(String publicationRequestId, String draftSha256,
            PolicyPublicationJobDocument.State state, int attempts, int recoveryCount, String failureCode,
            Instant leaseUntil, boolean alreadyCommitted, boolean retrySafe,
            List<PolicyPublicationAttemptDocument> attemptHistory,
            List<PolicyPublicationRecoveryEventDocument> recoveryHistory) { }
}
