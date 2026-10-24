package com.frauddetection.alert.governance.modelpromotion;

import com.mongodb.MongoException;
import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import tools.jackson.databind.ObjectMapper;

import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceConflictException;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceService;
import com.frauddetection.alert.governance.modelpolicy.PolicyProposalDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyPublicationJobDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyPublicationAttemptDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyPublicationRecoveryEventDocument;
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

/** Queues one bounded worker retry; this process never handles the issuer signing key. */
@Service
public class PolicyPublicationRecoveryService {
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
                "publicationRequestId").is(job.id())).with(Sort.by(Sort.Direction.ASC, "recoveryOrdinal"))
                .limit(PolicyPublicationLimits.MAX_RECOVERIES),
                PolicyPublicationRecoveryEventDocument.class);
        List<PolicyPublicationAttemptDocument> attempts = mongo.find(Query.query(Criteria.where(
                "publicationRequestId").is(job.id())).with(Sort.by(Sort.Direction.ASC, "attempt"))
                .limit(PolicyPublicationLimits.MAX_TOTAL_ATTEMPTS),
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
        for (int retry = 0; retry < 8; retry++) {
            try {
                transactions.executeWithoutResult(ignored -> {
                    PolicyPublicationRecoveryEventDocument replay = mongo.findById(operationId,
                            PolicyPublicationRecoveryEventDocument.class);
                    if (replay != null) {
                        if (!sameRecoveryIntent(replay, initial, actor, proposalId)) {
                            throw new PolicyGovernanceConflictException(
                                    "Recovery operation ID already belongs to another request");
                        }
                        return;
                    }
                    PolicyPublicationJobDocument job = requireJob(actor, proposalId);
                    requireIndependentRecoveryActor(actor, job);
                    if (!retrySafe(job)) {
                        throw new PolicyGovernanceConflictException(
                                "Publication cannot be retried by operator recovery");
                    }
                    // An equivalent commit is reconciled before signing. A conflicting revision is never retried.
                    equivalentPublicationCommitted(job);
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
                    mongo.insert(new PolicyPublicationRecoveryEventDocument(operationId, job.id(), job.proposalId(),
                            actor.id(), actor.issuer(), actor.subject(), job.draftSha256(), job.attempts(),
                            changed.recoveryCount(), job.failureCode(), now));
                });
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
                || !job.submissionEvidenceReference().equals("webauthn:"
                        + evidence.onlineApprovalProof().submission().evidence().id())
                || !job.approvalEvidenceReference().equals("webauthn:"
                        + evidence.onlineApprovalProof().approval().evidence().id())
                || !job.decidedAt().equals(evidence.onlineApprovalProof().approval().evidence().acceptedAt())
                || !GovernanceCanonicalJson.sha256(new ObjectMapper(), evidence.onlineApprovalProof())
                        .equals(evidence.approvalProofSha256())
                || !published.policy().canonicalSha256().equals(evidence.policyCanonicalSha256())
                || !job.draft().approvedContent(job.decidedAt()).equals(published.policy().content())
                || !job.draft().issuerIdentity().equals(published.policy().authorizationEvidence().authorityIdentity())
                || !job.draft().issuerKeyId().equals(published.policy().authorizationEvidence().keyId())
                || !published.policy().authorizationEvidence().signature().equals(evidence.policySignature())
                || !published.publishedAt().equals(evidence.publishedAt())) {
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
