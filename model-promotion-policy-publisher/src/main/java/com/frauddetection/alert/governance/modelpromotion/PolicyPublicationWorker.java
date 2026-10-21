package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceHistoryDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceState;
import com.frauddetection.alert.governance.modelpolicy.PolicyProposalDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyPublicationJobDocument;
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

/** Bounded Mongo lease worker. Only this process receives the Ed25519 issuer private key. */
final class PolicyPublicationWorker {
    private static final int MAX_ATTEMPTS = 5;
    private static final int LEASE_SECONDS = 120;
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

    PolicyPublicationWorker(MongoTemplate mongo, TransactionTemplate transactions,
            ModelPromotionPolicyPublisher publisher, OnlinePolicyApprovalVerifier approval,
            Ed25519ModelPromotionPolicyAuthorityVerifier issuerVerifier, PrivateKey issuerKey,
            PrivateKey publisherKey, InternalPolicyApprovalTrustRegistry approvalTrust,
            Clock clock, ObjectMapper mapper, String workerId) {
        this(mongo, transactions, publisher, approval, issuerVerifier, issuerKey, publisherKey, approvalTrust, clock, mapper,
                workerId, stage -> { });
    }

    PolicyPublicationWorker(MongoTemplate mongo, TransactionTemplate transactions,
            ModelPromotionPolicyPublisher publisher, OnlinePolicyApprovalVerifier approval,
            Ed25519ModelPromotionPolicyAuthorityVerifier issuerVerifier, PrivateKey issuerKey,
            PrivateKey publisherKey, InternalPolicyApprovalTrustRegistry approvalTrust,
            Clock clock, ObjectMapper mapper, String workerId, Checkpoints checkpoints) {
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
    }

    boolean runOnce() {
        PolicyPublicationJobDocument job = claim();
        if (job == null) return false;
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
            if (job.attempts() > MAX_ATTEMPTS) {
                fail(job, true);
                return true;
            }
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
            ModelPromotionPolicyPublicationReceipt receipt = publisher.publishOnlinePrepared(prepared);
            checkpoints.at(Stage.AFTER_COMMIT);
            complete(job, receipt);
            return true;
        } catch (RuntimeException failure) {
            try {
                if (recoverCommitted(job)) return true;
            } catch (SecurityException conflict) {
                fail(job, true);
                return true;
            } catch (RuntimeException uncertain) {
                // A failed primary read is not proof that an uncertain publication did not commit.
                return true;
            }
            fail(job, failure instanceof SecurityException
                    || failure instanceof MongoModelPromotionPolicyPublicationStore.PublicationConflictException);
            return true;
        }
    }

    private PolicyPublicationJobDocument claim() {
        Instant now = clock.instant();
        Query query = Query.query(new Criteria().orOperator(
                new Criteria().andOperator(Criteria.where("attempts").lt(MAX_ATTEMPTS),
                        Criteria.where("state").in(PolicyPublicationJobDocument.State.PENDING,
                                PolicyPublicationJobDocument.State.RETRYABLE)),
                new Criteria().andOperator(Criteria.where("state")
                        .is(PolicyPublicationJobDocument.State.CLAIMED),
                        Criteria.where("leaseUntil").lt(now))));
        query.with(Sort.by(Sort.Direction.ASC, "requestedAt"));
        return mongo.findAndModify(query,
                new Update().set("state", PolicyPublicationJobDocument.State.CLAIMED)
                        .set("leaseOwner", workerId).set("leaseUntil", now.plusSeconds(LEASE_SECONDS))
                        .inc("attempts", 1).unset("failureCode"),
                FindAndModifyOptions.options().returnNew(true), PolicyPublicationJobDocument.class);
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
        try {
            String key = ModelPromotionPolicyDocument.policyKey(job.draft().governanceScope(),
                    job.draft().intendedUse(), job.draft().policyId(), job.draft().revision());
            ModelPromotionPolicyDocument saved = mongo.findById(key, ModelPromotionPolicyDocument.class);
            if (saved == null) return false;
            InternalPolicyApprovalEvidenceDocument evidence = mongo.findById(key,
                    InternalPolicyApprovalEvidenceDocument.class);
            if (evidence == null || evidence.onlineApprovalProof() == null
                    || !job.id().equals(evidence.onlineApprovalProof().publicationRequestId())
                    || !job.draftSha256().equals(evidence.onlineApprovalProof().draftSha256())
                    || !job.draft().equals(evidence.onlineApprovalProof().draft())
                    || !saved.policy().canonicalSha256().equals(evidence.policyCanonicalSha256())
                    || !saved.policy().authorizationEvidence().signature().equals(evidence.policySignature())
                    || !issuerVerifier.inspectHistoricalIntegrity(saved.policy()).status().name()
                            .startsWith("INTACT_")) {
                throw new SecurityException("Published revision or approval evidence conflicts with the job");
            }
            PolicyPublicationJobDocument current = mongo.findById(job.id(), PolicyPublicationJobDocument.class);
            PolicyProposalDocument proposal = mongo.findById(job.proposalId(), PolicyProposalDocument.class);
            if (current != null && current.state() == PolicyPublicationJobDocument.State.PUBLISHED
                    && proposal != null && proposal.status() == PolicyGovernanceState.PUBLISHED
                    && saved.policy().canonicalSha256().equals(current.publishedPolicySha256())) {
                return true;
            }
            complete(job, new ModelPromotionPolicyPublicationReceipt(job.draft().policyId(),
                    job.draft().revision(), saved.policy().canonicalSha256(),
                    saved.policy().authorizationEvidence().authorityIdentity(),
                    saved.policy().authorizationEvidence().keyId(), saved.publishedAt(), true));
            return true;
        } catch (SecurityException conflict) {
            throw conflict;
        }
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
            mongo.insert(new PolicyGovernanceHistoryDocument(UUID.randomUUID().toString(), committed.id(),
                    committed.version(), "PUBLISH_POLICY", PolicyGovernanceState.PUBLICATION_PENDING,
                    PolicyGovernanceState.PUBLISHED, job.draft().publisherIdentity(), "publish:" + job.id(),
                    GovernanceCanonicalJson.sha256(mapper, Map.of("jobId", job.id(),
                            "policySha256", receipt.canonicalSha256())), job.draftSha256(),
                    job.approvalEvidenceReference(), clock.instant()));
        });
    }

    private void fail(PolicyPublicationJobDocument job, boolean permanent) {
        Query lease = Query.query(Criteria.where("_id").is(job.id())
                .and("state").is(PolicyPublicationJobDocument.State.CLAIMED)
                .and("leaseOwner").is(workerId).and("attempts").is(job.attempts()));
        boolean blocked = permanent || job.attempts() >= MAX_ATTEMPTS;
        mongo.updateFirst(lease, new Update().set("state", blocked
                        ? PolicyPublicationJobDocument.State.BLOCKED
                        : PolicyPublicationJobDocument.State.RETRYABLE)
                .set("failureCode", blocked ? "PUBLICATION_BLOCKED" : "PUBLICATION_RETRYABLE")
                .unset("leaseOwner").unset("leaseUntil"), PolicyPublicationJobDocument.class);
    }

    enum Stage { BEFORE_SIGN, AFTER_SIGN, AFTER_COMMIT }
    interface Checkpoints { void at(Stage stage); }
}
