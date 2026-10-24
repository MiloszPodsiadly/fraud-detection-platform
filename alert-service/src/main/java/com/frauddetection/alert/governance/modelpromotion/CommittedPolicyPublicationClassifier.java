package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceHistoryDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceState;
import com.frauddetection.alert.governance.modelpolicy.PolicyProposalDocument;
import com.frauddetection.alert.governance.modelpolicy.PolicyPublicationJobDocument;
import com.mongodb.MongoException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.time.Instant;

/** Read-only classification of the original immutable publication, shared by worker and recovery. */
public final class CommittedPolicyPublicationClassifier {
    public enum Kind { COMMITTED_EXACT, NO_COMMIT_PROVEN, IMMUTABLE_CONFLICT,
        INCOMPLETE_OR_UNAVAILABLE_EVIDENCE, OUTCOME_UNKNOWN }

    public record VerifiedReceipt(String policyId, long revision, String canonicalSha256,
            String issuerIdentity, String issuerKeyId, Instant publishedAt) { }

    record Result(Kind kind, String diagnostic, VerifiedReceipt receipt) {
        boolean exact() { return kind == Kind.COMMITTED_EXACT; }
    }

    private final MongoTemplate mongo;
    private final ObjectMapper mapper;
    private final OnlinePolicyApprovalVerifier approval;
    private final Ed25519ModelPromotionPolicyAuthorityVerifier issuer;

    CommittedPolicyPublicationClassifier(MongoTemplate mongo, ObjectMapper mapper,
            OnlinePolicyApprovalVerifier approval, Ed25519ModelPromotionPolicyAuthorityVerifier issuer) {
        this.mongo = Objects.requireNonNull(mongo);
        this.mapper = Objects.requireNonNull(mapper);
        this.approval = approval;
        this.issuer = issuer;
    }

    Result classify(PolicyPublicationJobDocument job) {
        try {
            if (job == null || job.draft() == null || job.id() == null || job.proposalId() == null)
                return result(Kind.IMMUTABLE_CONFLICT, "INVALID_JOB");
            var proposal = mongo.findById(job.proposalId(), PolicyProposalDocument.class);
            if (proposal == null || !job.id().equals(proposal.publicationRequestId())
                    || !job.draft().equals(proposal.draft())
                    || !job.draftSha256().equals(proposal.draftSha256())
                    || !job.decidedAt().equals(proposal.decidedAt())
                    || !job.approvalEvidenceReference().equals(proposal.approvalEvidenceReference())
                    || !(proposal.status() == PolicyGovernanceState.PUBLICATION_PENDING
                            || proposal.status() == PolicyGovernanceState.PUBLISHED)
                    || proposal.version() != job.proposalVersion()
                        + (proposal.status() == PolicyGovernanceState.PUBLISHED ? 1 : 0)
                    || !job.draftSha256().equals(job.draft().canonicalSha256(mapper)))
                return result(Kind.IMMUTABLE_CONFLICT, "JOB_PROPOSAL_DIFFERS");
            String key = ModelPromotionPolicyDocument.policyKey(job.draft().governanceScope(),
                    job.draft().intendedUse(), job.draft().policyId(), job.draft().revision());
            var published = mongo.findById(key, ModelPromotionPolicyDocument.class);
            var evidence = mongo.findById(key, InternalPolicyApprovalEvidenceDocument.class);
            if (published == null && evidence == null && proposal.status() == PolicyGovernanceState.PUBLISHED)
                return result(Kind.IMMUTABLE_CONFLICT, "ACKNOWLEDGED_POLICY_MISSING");
            if (published == null && evidence == null
                    && (job.publishedPolicySha256() != null || job.publishedAt() != null))
                return result(Kind.IMMUTABLE_CONFLICT, "ACKNOWLEDGEMENT_RECEIPT_WITHOUT_POLICY");
            if (published == null && evidence == null)
                return result(Kind.NO_COMMIT_PROVEN, "POLICY_AND_EVIDENCE_ABSENT");
            if (published == null || evidence == null || published.publishedAt() == null)
                return result(Kind.INCOMPLETE_OR_UNAVAILABLE_EVIDENCE, "PARTIAL_COMMIT_MATERIAL");
            if (approval == null || issuer == null)
                return result(Kind.INCOMPLETE_OR_UNAVAILABLE_EVIDENCE, "VERIFIER_NOT_CONFIGURED");
            if (!job.draft().approvedContent(job.decidedAt()).equals(published.policy().content())
                    || !key.equals(published.policyKey()) || !key.equals(evidence.policyKey())
                    || !published.policy().canonicalSha256().equals(evidence.policyCanonicalSha256())
                    || !published.policy().authorizationEvidence().signature().equals(evidence.policySignature())
                    || !published.publishedAt().equals(evidence.publishedAt()))
                return result(Kind.IMMUTABLE_CONFLICT, "JOB_PROPOSAL_OR_POLICY_DIFFERS");
            if (job.state() == PolicyPublicationJobDocument.State.PUBLISHED
                    && (!published.policy().canonicalSha256().equals(job.publishedPolicySha256())
                        || !published.publishedAt().equals(job.publishedAt())
                        || proposal.status() != PolicyGovernanceState.PUBLISHED)
                    || proposal.status() == PolicyGovernanceState.PUBLISHED
                        && job.state() != PolicyPublicationJobDocument.State.PUBLISHED)
                return result(Kind.IMMUTABLE_CONFLICT, "ACKNOWLEDGEMENT_RECEIPT_DIFFERS");
            var proof = evidence.onlineApprovalProof();
            if (proof == null || !job.id().equals(proof.publicationRequestId())
                    || !job.draft().equals(proof.draft())
                    || !job.draftSha256().equals(proof.draftSha256())
                    || !job.submissionEvidenceReference().equals("webauthn:"
                            + proof.submission().evidence().id())
                    || !job.approvalEvidenceReference().equals("webauthn:"
                            + proof.approval().evidence().id())
                    || !job.decidedAt().equals(proof.approval().evidence().acceptedAt())
                    || !GovernanceCanonicalJson.sha256(mapper, proof).equals(evidence.approvalProofSha256()))
                return result(Kind.IMMUTABLE_CONFLICT, "ORIGINAL_APPROVAL_DIFFERS");
            approval.verifyCommitted(published.policy(), evidence);
            var integrity = issuer.inspectHistoricalIntegrity(published.policy());
            if (integrity.status() == ModelPromotionPolicyHistoricalIntegrity.Status.UNAVAILABLE)
                return result(Kind.INCOMPLETE_OR_UNAVAILABLE_EVIDENCE, "ISSUER_VERIFIER_UNAVAILABLE");
            if (integrity.status() == ModelPromotionPolicyHistoricalIntegrity.Status.INVALID)
                return result(Kind.IMMUTABLE_CONFLICT, "ISSUER_SIGNATURE_INVALID");
            Query historyQuery = Query.query(Criteria.where("proposalId").is(job.proposalId())
                    .and("action").is("PUBLISH_POLICY").and("operationId").is("publish:" + job.id()));
            long historyCount = mongo.count(historyQuery, PolicyGovernanceHistoryDocument.class);
            if (historyCount > 1 || proposal.status() == PolicyGovernanceState.PUBLICATION_PENDING
                    && historyCount != 0 || proposal.status() == PolicyGovernanceState.PUBLISHED
                    && historyCount != 1)
                return result(Kind.IMMUTABLE_CONFLICT, "PUBLICATION_HISTORY_DIFFERS");
            if (historyCount == 1) {
                var history = mongo.findOne(historyQuery, PolicyGovernanceHistoryDocument.class);
                if (history == null || history.toStatus() != PolicyGovernanceState.PUBLISHED
                        || !job.draftSha256().equals(history.draftSha256())
                        || !job.approvalEvidenceReference().equals(history.approvalEvidenceReference()))
                    return result(Kind.IMMUTABLE_CONFLICT, "PUBLICATION_HISTORY_DIFFERS");
            }
            return new Result(Kind.COMMITTED_EXACT, "SIGNED_PUBLICATION_VERIFIED",
                    new VerifiedReceipt(job.draft().policyId(),
                            job.draft().revision(), published.policy().canonicalSha256(),
                            published.policy().authorizationEvidence().authorityIdentity(),
                            published.policy().authorizationEvidence().keyId(), published.publishedAt()));
        } catch (DataAccessException | MongoException unavailable) {
            return result(Kind.OUTCOME_UNKNOWN, "MONGO_READ_UNAVAILABLE");
        } catch (SecurityException | IllegalArgumentException invalid) {
            return result(Kind.IMMUTABLE_CONFLICT, "CRYPTOGRAPHIC_OR_CONTRACT_INVALID");
        } catch (RuntimeException unavailable) {
            return result(Kind.INCOMPLETE_OR_UNAVAILABLE_EVIDENCE, "VERIFICATION_UNAVAILABLE");
        }
    }

    private Result result(Kind kind, String diagnostic) {
        return new Result(kind, diagnostic, null);
    }
}
