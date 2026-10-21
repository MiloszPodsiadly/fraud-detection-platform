package com.frauddetection.alert.governance.modelpolicy;

import com.frauddetection.alert.governance.modelpromotion.ModelPromotionPolicyFullTrustVerifier;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionPolicyDocument;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/** A read-only view of durable publication state and the current real signature-verification result. */
@RestController
@RequestMapping("/api/v1/governance/model-policies")
public class PolicyPublicationStatusController {
    private final PolicyGovernanceService governance;
    private final MongoTemplate mongo;
    private final ModelPromotionPolicyFullTrustVerifier verifier;

    public PolicyPublicationStatusController(PolicyGovernanceService governance, MongoTemplate mongo,
            ModelPromotionPolicyFullTrustVerifier verifier) {
        this.governance = governance;
        this.mongo = mongo;
        this.verifier = verifier;
    }

    @GetMapping("/{id}/publication")
    public Status publication(Authentication authentication, @PathVariable String id) {
        if (id == null || !id.matches("^[A-Za-z0-9-]{1,128}$")) {
            throw new IllegalArgumentException("Invalid proposal ID");
        }
        PolicyProposalDocument proposal = governance.get(PolicyGovernanceActor.from(authentication), id);
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
                verification.status() == com.frauddetection.alert.governance.modelpromotion.ModelPromotionPolicyTrustVerification.Status.FULLY_AUTHORIZED
                        ? "NONE" : verification.signature().status() != com.frauddetection.alert.governance.modelpromotion.ModelPromotionPolicyAuthorityVerification.Status.VERIFIED
                                ? verification.signature().reason().name() : verification.approvalEvidence().reason().name(),
                null, verification.signature().status().name(), verification.approvalEvidence().status().name());
    }

    public record Status(String requestId, String state, String policySha256, Instant publishedAt,
            String verificationStatus, String verificationReason, String failureCode,
            String signatureStatus, String approvalEvidenceStatus) { }
}
