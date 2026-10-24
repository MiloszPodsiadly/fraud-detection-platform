package com.frauddetection.alert.governance.modelpromotion;

import com.mongodb.MongoException;
import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.frauddetection.alert.governance.audit.GovernanceEventCommitment;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceHistoryDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceState;
import com.frauddetection.alert.governance.modelpolicy.PolicyProposalDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyPublicationJobDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyPublicationAttemptDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyPublicationLimits;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.security.PrivateKey;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Bounded Mongo lease worker. Only this process receives the Ed25519 issuer private key. */
final class PolicyPublicationWorker {
    private final MongoTemplate mongo;
    private final TransactionTemplate transactions;
    private final ModelPromotionPolicyPublisher publisher;
    private final OnlinePolicyApprovalVerifier approval;
    private final Ed25519ModelPromotionPolicyAuthorityVerifier issuerVerifier;
    private final PrivateKey issuerKey;
    private final PrivateKey publisherKey;
    private final InternalPolicyApprovalTrustRegistry approvalTrust;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final String workerId;
    private final Checkpoints checkpoints;
    private final BooleanSupplier fleetConsistent;
    private final CommittedPolicyPublicationClassifier committed;

    PolicyPublicationWorker(MongoTemplate mongo, TransactionTemplate transactions,
            ModelPromotionPolicyPublisher publisher, OnlinePolicyApprovalVerifier approval,
            Ed25519ModelPromotionPolicyAuthorityVerifier issuerVerifier, PrivateKey issuerKey,
            PrivateKey publisherKey, InternalPolicyApprovalTrustRegistry approvalTrust,
            Clock clock, ObjectMapper mapper, String workerId, BooleanSupplier fleetConsistent) {
        this(mongo, transactions, publisher, approval, issuerVerifier, issuerKey, publisherKey, approvalTrust, clock, mapper,
                workerId, stage -> { }, fleetConsistent);
    }

    PolicyPublicationWorker(MongoTemplate mongo, TransactionTemplate transactions,
            ModelPromotionPolicyPublisher publisher, OnlinePolicyApprovalVerifier approval,
            Ed25519ModelPromotionPolicyAuthorityVerifier issuerVerifier, PrivateKey issuerKey,
            PrivateKey publisherKey, InternalPolicyApprovalTrustRegistry approvalTrust,
            Clock clock, ObjectMapper mapper, String workerId, Checkpoints checkpoints,
            BooleanSupplier fleetConsistent) {
        this.mongo = Objects.requireNonNull(mongo);
        this.transactions = Objects.requireNonNull(transactions);
        this.publisher = Objects.requireNonNull(publisher);
        this.approval = Objects.requireNonNull(approval);
        this.issuerVerifier = Objects.requireNonNull(issuerVerifier);
        this.issuerKey = Objects.requireNonNull(issuerKey);
        this.publisherKey = Objects.requireNonNull(publisherKey);
        this.approvalTrust = Objects.requireNonNull(approvalTrust);
        this.clock = Objects.requireNonNull(clock);
        this.mapper = Objects.requireNonNull(mapper);
        this.workerId = Objects.requireNonNull(workerId);
        this.checkpoints = Objects.requireNonNull(checkpoints);
        this.fleetConsistent = Objects.requireNonNull(fleetConsistent);
        this.committed = new CommittedPolicyPublicationClassifier(mongo, mapper, approval, issuerVerifier);
    }

    boolean runOnce() {
        Claimed claimed = claim(fleetConsistent.getAsBoolean());
        if (claimed == null) return false;
        PolicyPublicationJobDocument job = claimed.job();
        try {
            requireLease(job);
            PolicyProposalDocument proposal = mongo.findById(job.proposalId(), PolicyProposalDocument.class);
            if (proposal == null || proposal.status() != PolicyGovernanceState.PUBLICATION_PENDING
                    || proposal.version() != job.proposalVersion()
                    || !job.id().equals(proposal.publicationRequestId())
                    || !job.draft().equals(proposal.draft())
                    || !job.draftSha256().equals(proposal.draftSha256())
                    || !job.decidedAt().equals(proposal.decidedAt())
                    || !job.approvalEvidenceReference().equals(proposal.approvalEvidenceReference())) {
                throw new SecurityException("Pending publication proposal changed");
            }
            if (recoverCommitted(job)) return true;
            if (claimed.reconcileOnly()) {
                int authorized = PolicyPublicationLimits.authorizedAttempts(job.recoveryCount());
                fail(job, job.attempts() == authorized ? "ATTEMPTS_EXHAUSTED"
                        : job.attempts() < authorized ? "OPERATIONAL_RETRYABLE"
                        : "RECOVERY_BUDGET_INCONSISTENT");
                return true;
            }
            if (job.attempts() > PolicyPublicationLimits.authorizedAttempts(job.recoveryCount())) {
                fail(job, "RECOVERY_BUDGET_EXHAUSTED");
                return true;
            }
            requireFleet();
            OnlinePolicyApprovalVerifier.Verified verified = approval.verify(job);
            requirePublisherKey(job, verified.authorization());
            checkpoints.at(Stage.BEFORE_SIGN);
            var proposed = new ModelPromotionPolicyPublisher.Draft(job.draft(), job.draftSha256());
            var prepared = publisher.prepareOnline(proposed, issuerKey, job.draft().issuerIdentity(),
                    job.draft().issuerKeyId(), job.decidedAt(), verified);
            checkpoints.at(Stage.AFTER_SIGN);
            requireLease(job);
            // Recheck revocation and public evidence immediately before the irreversible publication attempt.
            OnlinePolicyApprovalVerifier.Verified current = approval.verify(job);
            if (!current.equals(verified)) {
                throw new SecurityException("Approval mandate or evidence changed before publication");
            }
            requirePublisherKey(job, current.authorization());
            requireLease(job);
            requireFleet();
            ModelPromotionPolicyPublicationReceipt receipt = publisher.publishOnlinePrepared(prepared,
                    new MongoModelPromotionPolicyPublicationStore.PublicationLease(job.id(), workerId,
                            job.attempts(), clock.instant()));
            checkpoints.at(Stage.AFTER_COMMIT);
            complete(job, receipt);
            return true;
        } catch (RuntimeException failure) {
            try {
                if (recoverCommitted(job)) return true;
            } catch (SecurityException conflict) {
                fail(job, "IMMUTABLE_CONFLICT");
                return true;
            } catch (RuntimeException uncertain) {
                // A failed primary read is not proof that an uncertain publication did not commit.
                if (claimed.reconcileOnly()
                        && job.reconciliationCount() >= PolicyPublicationLimits.MAX_RECONCILIATIONS) {
                    fail(job, "RECONCILIATION_UNAVAILABLE");
                }
                return true;
            }
            if (failure instanceof MongoModelPromotionPolicyPublicationStore.PublicationOutcomeUncertainException) {
                // Leave the claim intact for lease-expiry reconciliation. No fresh signature is attempted now.
                return true;
            }
            String reason = failure instanceof FleetInconsistentException
                    ? "FLEET_TRUST_INCONSISTENT"
                    : failure instanceof MongoModelPromotionPolicyPublicationStore.PublicationConflictException
                    ? "IMMUTABLE_CONFLICT" : failure instanceof SecurityException
                            ? "CURRENT_TRUST_OR_EVIDENCE_INVALID" : job.attempts() >= PolicyPublicationLimits.BASE_ATTEMPTS
                                    ? "ATTEMPTS_EXHAUSTED" : "OPERATIONAL_RETRYABLE";
            fail(job, reason);
            return true;
        }
    }

    private void requireFleet() {
        if (!fleetConsistent.getAsBoolean()) throw new FleetInconsistentException();
    }

    private Claimed claim(boolean allowNewAttempt) {
        for (int retry = 0; retry < 4; retry++) {
            try {
                return claimTransactional(allowNewAttempt);
            } catch (RuntimeException failure) {
                if (!transientTransactionConflict(failure)) throw failure;
            }
        }
        // Another worker is making progress; the next poll can re-evaluate durable state.
        return null;
    }

    private boolean transientTransactionConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoException mongoFailure
                    && mongoFailure.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) return true;
        }
        return false;
    }

    private Claimed claimTransactional(boolean allowNewAttempt) {
        Instant now = clock.instant();
        Query pending = Query.query(Criteria.where("state").is(PolicyPublicationJobDocument.State.PENDING)
                        .and("attempts").lt(PolicyPublicationLimits.BASE_ATTEMPTS))
                .with(Sort.by(Sort.Direction.ASC, "requestedAt"));
        var retryBudgets = new java.util.ArrayList<Criteria>();
        for (int recoveries = 0; recoveries <= PolicyPublicationLimits.MAX_RECOVERIES; recoveries++) {
            var budget = new Criteria().andOperator(
                    Criteria.where("recoveryCount").is(recoveries),
                    Criteria.where("attempts").lt(PolicyPublicationLimits.authorizedAttempts(recoveries)),
                    Criteria.where("state").is(PolicyPublicationJobDocument.State.RETRYABLE));
            retryBudgets.add(budget);
        }
        Query retryable = Query.query(new Criteria().orOperator(retryBudgets.toArray(Criteria[]::new)))
                .with(Sort.by(Sort.Direction.ASC, "requestedAt"));
        Query reconcile = Query.query(Criteria.where("state").is(PolicyPublicationJobDocument.State.CLAIMED)
                        .and("leaseUntil").lt(now)
                        .and("reconciliationCount").lt(PolicyPublicationLimits.MAX_RECONCILIATIONS))
                .with(Sort.by(Sort.Direction.ASC, "requestedAt"));
        return transactions.execute(ignored -> {
            PolicyPublicationJobDocument job = allowNewAttempt ? mongo.findAndModify(pending,
                    new Update().set("state", PolicyPublicationJobDocument.State.CLAIMED)
                            .set("leaseOwner", workerId).set("leaseUntil", now.plusSeconds(PolicyPublicationLimits.LEASE_SECONDS))
                            .inc("attempts", 1).unset("failureCode"),
                    FindAndModifyOptions.options().returnNew(true), PolicyPublicationJobDocument.class) : null;
            if (job == null && allowNewAttempt) {
                var candidate = mongo.findOne(retryable, PolicyPublicationJobDocument.class);
                if (candidate != null) {
                    var classification = committed.classify(candidate);
                    var exact = Query.query(Criteria.where("_id").is(candidate.id())
                            .and("state").is(PolicyPublicationJobDocument.State.RETRYABLE)
                            .and("attempts").is(candidate.attempts())
                            .and("recoveryCount").is(candidate.recoveryCount())
                            .and("publicationFence").is(candidate.publicationFence()));
                    if (classification.exact()
                            && candidate.reconciliationCount() < PolicyPublicationLimits.MAX_RECONCILIATIONS) {
                        job = mongo.findAndModify(exact, new Update()
                                        .set("state", PolicyPublicationJobDocument.State.CLAIMED)
                                        .set("leaseOwner", workerId)
                                        .set("leaseUntil", now.plusSeconds(PolicyPublicationLimits.LEASE_SECONDS))
                                        .inc("reconciliationCount", 1),
                                FindAndModifyOptions.options().returnNew(true),
                                PolicyPublicationJobDocument.class);
                        if (job == null) throw new IllegalStateException("Retryable job changed during reconciliation");
                        return new Claimed(job, true);
                    }
                    if (classification.kind() != CommittedPolicyPublicationClassifier.Kind.NO_COMMIT_PROVEN) {
                        String reason = classification.kind()
                                == CommittedPolicyPublicationClassifier.Kind.IMMUTABLE_CONFLICT
                                ? "IMMUTABLE_CONFLICT" : "RECONCILIATION_UNAVAILABLE";
                        var blocked = mongo.updateFirst(exact, new Update()
                                .set("state", PolicyPublicationJobDocument.State.BLOCKED)
                                .set("failureCode", reason), PolicyPublicationJobDocument.class);
                        if (blocked.getModifiedCount() != 1)
                            throw new IllegalStateException("Retryable job changed before blocking unsafe retry");
                        return null;
                    }
                    job = mongo.findAndModify(exact, new Update()
                                    .set("state", PolicyPublicationJobDocument.State.CLAIMED)
                                    .set("leaseOwner", workerId)
                                    .set("leaseUntil", now.plusSeconds(PolicyPublicationLimits.LEASE_SECONDS))
                                    .inc("attempts", 1).unset("failureCode"),
                            FindAndModifyOptions.options().returnNew(true), PolicyPublicationJobDocument.class);
                    if (job == null) throw new IllegalStateException("Retryable job changed before new attempt");
                }
            }
            if (job != null) {
                if (job.attempts() > 1) mongo.updateFirst(Query.query(Criteria.where("_id")
                                .is(job.id() + ":" + (job.attempts() - 1)).and("outcome").is("CLAIMED")),
                        new Update().set("outcome", "LEASE_EXPIRED_UNCONFIRMED").set("endedAt", now),
                        PolicyPublicationAttemptDocument.class);
                mongo.insert(new PolicyPublicationAttemptDocument(job.id() + ":" + job.attempts(), job.id(),
                        job.attempts(), workerId, now, "CLAIMED", null, null));
                return new Claimed(job, false);
            }
            job = mongo.findAndModify(reconcile,
                    new Update().set("leaseOwner", workerId)
                            .set("leaseUntil", now.plusSeconds(PolicyPublicationLimits.LEASE_SECONDS))
                            .inc("reconciliationCount", 1),
                    FindAndModifyOptions.options().returnNew(true), PolicyPublicationJobDocument.class);
            if (job == null) return null;
            mongo.updateFirst(Query.query(Criteria.where("_id").is(job.id() + ":" + job.attempts())
                            .and("outcome").is("CLAIMED")),
                    new Update().set("outcome", "LEASE_EXPIRED_UNCONFIRMED").set("endedAt", now),
                    PolicyPublicationAttemptDocument.class);
            return new Claimed(job, true);
        });
    }

    private void requireLease(PolicyPublicationJobDocument job) {
        PolicyPublicationJobDocument current = mongo.findById(job.id(), PolicyPublicationJobDocument.class);
        if (current == null || current.state() != PolicyPublicationJobDocument.State.CLAIMED
                || !workerId.equals(current.leaseOwner()) || current.attempts() != job.attempts()
                || !clock.instant().isBefore(current.leaseUntil())) {
            throw new IllegalStateException("Publication lease was lost");
        }
    }

    private void requirePublisherKey(PolicyPublicationJobDocument job,
            InstitutionalPolicyPublicationAuthorization.Authorization authorization) {
        var trusted = approvalTrust.find(job.draft().publisherIdentity(), job.draft().publisherKeyId());
        if (trusted == null || !trusted.publicKeySha256().equals(authorization.publisherPublicKeySha256())) {
            throw new SecurityException("Publisher key mandate changed");
        }
        try {
            byte[] challenge = new byte[32];
            new SecureRandom().nextBytes(challenge);
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(publisherKey);
            signer.update(challenge);
            byte[] signed = signer.sign();
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(trusted.publicKey());
            verifier.update(challenge);
            if (!verifier.verify(signed)) throw new SecurityException("Publisher private key is unavailable");
        } catch (GeneralSecurityException failure) {
            throw new SecurityException("Publisher private key is unavailable", failure);
        }
    }

    private boolean recoverCommitted(PolicyPublicationJobDocument job) {
        var result = committed.classify(job);
        if (result.kind() == CommittedPolicyPublicationClassifier.Kind.NO_COMMIT_PROVEN) return false;
        if (result.kind() == CommittedPolicyPublicationClassifier.Kind.IMMUTABLE_CONFLICT)
            throw new SecurityException(result.diagnostic());
        if (!result.exact()) throw new IllegalStateException(result.diagnostic());
        PolicyPublicationJobDocument current = mongo.findById(job.id(), PolicyPublicationJobDocument.class);
        PolicyProposalDocument proposal = mongo.findById(job.proposalId(), PolicyProposalDocument.class);
        if (current != null && current.state() == PolicyPublicationJobDocument.State.PUBLISHED
                && proposal != null && proposal.status() == PolicyGovernanceState.PUBLISHED
                && result.receipt().canonicalSha256().equals(current.publishedPolicySha256())) return true;
        complete(job, new ModelPromotionPolicyPublicationReceipt(result.receipt().policyId(),
                result.receipt().revision(), result.receipt().canonicalSha256(),
                result.receipt().issuerIdentity(), result.receipt().issuerKeyId(),
                result.receipt().publishedAt(), true));
        return true;
    }

    private void complete(PolicyPublicationJobDocument job, ModelPromotionPolicyPublicationReceipt receipt) {
        transactions.executeWithoutResult(status -> {
            Query lease = Query.query(Criteria.where("_id").is(job.id())
                    .and("state").is(PolicyPublicationJobDocument.State.CLAIMED)
                    .and("leaseOwner").is(workerId).and("attempts").is(job.attempts()));
            PolicyPublicationJobDocument updated = mongo.findAndModify(lease,
                    new Update().set("state", PolicyPublicationJobDocument.State.PUBLISHED)
                            .set("publishedPolicySha256", receipt.canonicalSha256())
                            .set("publishedAt", receipt.publishedAt())
                            .unset("leaseOwner").unset("leaseUntil").unset("failureCode"),
                    FindAndModifyOptions.options().returnNew(true), PolicyPublicationJobDocument.class);
            if (updated == null) throw new IllegalStateException("Publication lease changed before acknowledgement");
            mongo.updateFirst(Query.query(Criteria.where("_id").is(job.id() + ":" + job.attempts())),
                    new Update().set("outcome", "PUBLISHED").set("endedAt", clock.instant()),
                    PolicyPublicationAttemptDocument.class);
            Query pending = Query.query(Criteria.where("_id").is(job.proposalId())
                    .and("status").is(PolicyGovernanceState.PUBLICATION_PENDING)
                    .and("version").is(job.proposalVersion())
                    .and("publicationRequestId").is(job.id())
                    .and("draftSha256").is(job.draftSha256()));
            PolicyProposalDocument committed = mongo.findAndModify(pending,
                    new Update().set("status", PolicyGovernanceState.PUBLISHED)
                            .inc("version", 1).set("updatedAt", clock.instant()),
                    FindAndModifyOptions.options().returnNew(true), PolicyProposalDocument.class);
            if (committed == null) throw new SecurityException("Publication proposal changed before acknowledgement");
            var history = new PolicyGovernanceHistoryDocument(UUID.randomUUID().toString(), committed.id(),
                    committed.version(), "PUBLISH_POLICY", PolicyGovernanceState.PUBLICATION_PENDING,
                    PolicyGovernanceState.PUBLISHED, job.draft().publisherIdentity(), "publish:" + job.id(),
                    GovernanceCanonicalJson.sha256(mapper, Map.of("jobId", job.id(),
                            "policySha256", receipt.canonicalSha256())), job.draftSha256(),
                    job.approvalEvidenceReference(), clock.instant(), null);
            mongo.insert(history);
            GovernanceEventCommitment.record(mongo, GovernanceEventCommitment.Kind.POLICY_PUBLICATION,
                    history.id(), history, job.draft().governanceScope().institutionalScope(),
                    job.draft().publisherIdentity(), committed.id(), "publish:" + job.id(),
                    receipt.canonicalSha256(), receipt.policyId() + ":" + receipt.revision(),
                    history.committedAt());
        });
    }

    private void fail(PolicyPublicationJobDocument job, String reason) {
        transactions.executeWithoutResult(ignored -> {
            Query lease = Query.query(Criteria.where("_id").is(job.id())
                    .and("state").is(PolicyPublicationJobDocument.State.CLAIMED)
                    .and("leaseOwner").is(workerId).and("attempts").is(job.attempts()));
            boolean blocked = !("OPERATIONAL_RETRYABLE".equals(reason)
                    || "FLEET_TRUST_INCONSISTENT".equals(reason))
                    || job.attempts() >= PolicyPublicationLimits.BASE_ATTEMPTS;
            var changed = mongo.updateFirst(lease, new Update().set("state", blocked
                            ? PolicyPublicationJobDocument.State.BLOCKED
                            : PolicyPublicationJobDocument.State.RETRYABLE)
                    .set("failureCode", reason)
                    .set("reconciliationCount", "OPERATIONAL_RETRYABLE".equals(reason)
                            ? 0 : job.reconciliationCount())
                    .unset("leaseOwner").unset("leaseUntil"), PolicyPublicationJobDocument.class);
            if (changed.getModifiedCount() == 1) {
                mongo.updateFirst(Query.query(Criteria.where("_id").is(job.id() + ":" + job.attempts())
                                .and("outcome").in("CLAIMED", "LEASE_EXPIRED_UNCONFIRMED")),
                        new Update().set("outcome", blocked ? "BLOCKED" : "RETRYABLE")
                                .set("failureCode", reason).set("endedAt", clock.instant()),
                        PolicyPublicationAttemptDocument.class);
            }
        });
    }

    enum Stage { BEFORE_SIGN, AFTER_SIGN, AFTER_COMMIT }
    interface Checkpoints { void at(Stage stage); }
    private record Claimed(PolicyPublicationJobDocument job, boolean reconcileOnly) { }
    private static final class FleetInconsistentException extends RuntimeException { }
}
