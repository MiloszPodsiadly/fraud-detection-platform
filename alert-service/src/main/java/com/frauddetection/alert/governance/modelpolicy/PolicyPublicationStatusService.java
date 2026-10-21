package com.frauddetection.alert.governance.modelpolicy;

import com.frauddetection.alert.governance.modelpromotion.ModelPromotionPolicyFullTrustVerifier;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionPolicyDocument;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionPolicyTrustVerification;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionPolicyAuthorityVerification;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/** Authorized application read of durable publication state and current policy trust. */
@Service
public class PolicyPublicationStatusService {
    private final PolicyGovernanceService governance;
    private final MongoTemplate mongo;
    private final ModelPromotionPolicyFullTrustVerifier verifier;

    public PolicyPublicationStatusService(PolicyGovernanceService governance, MongoTemplate mongo,
            ModelPromotionPolicyFullTrustVerifier verifier) {
        this.governance = governance;
        this.mongo = mongo;
        this.verifier = verifier;
    }

    public Status publication(PolicyGovernanceActor actor, String id) {
        if (id == null || !id.matches("^[A-Za-z0-9-]{1,128}$")) {
            throw new IllegalArgumentException("Invalid proposal ID");
        }
        PolicyProposalDocument proposal = governance.get(actor, id);
        if (proposal.publicationRequestId() == null) {
            return new Status(null, "NOT_REQUESTED", null, null, "UNAVAILABLE", "NO_PUBLICATION", null, null, null);
        }
        PolicyPublicationJobDocument job = mongo.findById(proposal.publicationRequestId(),
                PolicyPublicationJobDocument.class);
        if (job == null || !proposal.id().equals(job.proposalId())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Publication receipt unavailable");
        }
        if (job.state() != PolicyPublicationJobDocument.State.PUBLISHED) {
            return new Status(job.id(), job.state().name(), null, null,
                    "UNAVAILABLE", "NOT_PUBLISHED", job.failureCode(), null, null);
        }
        String key = ModelPromotionPolicyDocument.policyKey(job.draft().governanceScope(),
                job.draft().intendedUse(), job.draft().policyId(), job.draft().revision());
        ModelPromotionPolicyDocument published = mongo.findById(key, ModelPromotionPolicyDocument.class);
        if (published == null || !published.policy().canonicalSha256().equals(job.publishedPolicySha256())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Signed policy receipt unavailable");
        }
        var verification = verifier.verify(published.policy());
        return new Status(job.id(), job.state().name(), job.publishedPolicySha256(), job.publishedAt(),
                verification.status().name(),
                verification.status() == ModelPromotionPolicyTrustVerification.Status.FULLY_AUTHORIZED
                        ? "NONE" : verification.signature().status() != ModelPromotionPolicyAuthorityVerification.Status.VERIFIED
                                ? verification.signature().reason().name() : verification.approvalEvidence().reason().name(),
                null, verification.signature().status().name(), verification.approvalEvidence().status().name());
    }

    public record Status(String requestId, String state, String policySha256, Instant publishedAt,
            String verificationStatus, String verificationReason, String failureCode,
            String signatureStatus, String approvalEvidenceStatus) { }
}
