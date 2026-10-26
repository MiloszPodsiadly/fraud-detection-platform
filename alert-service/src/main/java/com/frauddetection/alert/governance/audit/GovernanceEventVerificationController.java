package com.frauddetection.alert.governance.audit;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/governance/audit/commitments")
public class GovernanceEventVerificationController {
    private final GovernanceEventVerificationService verification;
    private final AuditChainRangeVerificationService rangeVerification;

    public GovernanceEventVerificationController(GovernanceEventVerificationService verification,
            AuditChainRangeVerificationService rangeVerification) {
        this.verification = verification;
        this.rangeVerification = rangeVerification;
    }

    @GetMapping("/{kind}/{eventId}")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.GOVERNANCE_AUDIT_COMMITMENT,
            resourceType = ReadAccessResourceType.AUDIT_COMMITMENT,
            resourceIdVariable = "eventId")
    public GovernanceEventVerificationService.Verification verify(Authentication authentication,
            @PathVariable GovernanceEventCommitment.Kind kind, @PathVariable String eventId) {
        return verification.verify(PolicyGovernanceActor.from(authentication), kind, eventId);
    }

    @GetMapping("/chain-ranges")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.GOVERNANCE_AUDIT_CHAIN_RANGE,
            resourceType = ReadAccessResourceType.AUDIT_CHAIN_RANGE)
    public AuditChainRangeVerificationService.Result verifyRange(Authentication authentication,
            @RequestParam("start_position") long startPosition,
            @RequestParam("end_position") long endPosition,
            @RequestParam(value = "boundary_position", required = false) Long boundaryPosition,
            @RequestParam(value = "boundary_sha256", required = false) String boundarySha256) {
        return rangeVerification.verify(PolicyGovernanceActor.from(authentication), startPosition, endPosition,
                boundaryPosition, boundarySha256);
    }

    @GetMapping("/model-promotion-reviews/{proposalId}/proof")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.GOVERNANCE_AUDIT_HISTORICAL_PROOF,
            resourceType = ReadAccessResourceType.MODEL_PROMOTION_REVIEW_PROOF,
            resourceIdVariable = "proposalId")
    public GovernanceEventVerificationService.HistoricalProofVerification verifyModelPromotionReviewProof(
            Authentication authentication, @PathVariable String proposalId) {
        return verification.verifyModelPromotionReviewProof(PolicyGovernanceActor.from(authentication), proposalId);
    }
}
