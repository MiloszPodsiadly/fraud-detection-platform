package com.frauddetection.alert.governance.modelpromotion.api;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnService;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionSignedReviewService;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionSignedReviewService.ReviewIntent;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionSignedReviewService.SignedReview;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/governance/model-promotion/proposals/{proposalId}/reviews")
public class ModelPromotionSignedReviewController {
    private static final String PROPOSAL_ID = "^[A-Za-z0-9._:@-]{1,128}$";
    private final ModelPromotionSignedReviewService signedReviews;
    private final ModelPromotionApiService api;
    private final ModelPromotionApiRateLimiter rateLimiter;

    public ModelPromotionSignedReviewController(ModelPromotionSignedReviewService signedReviews,
            ModelPromotionApiService api, ModelPromotionApiRateLimiter rateLimiter) {
        this.signedReviews = signedReviews;
        this.api = api;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/challenges")
    public PolicyWebAuthnService.CeremonyOptions start(Authentication authentication,
            @PathVariable @NotBlank @Pattern(regexp = PROPOSAL_ID) String proposalId,
            @Valid @RequestBody ReviewIntent request) {
        requireRateLimit(authentication);
        return signedReviews.start(PolicyGovernanceActor.fromOidcSession(authentication), proposalId, request);
    }

    @PostMapping("/signed")
    public ModelPromotionApiContracts.ProposalResponse finish(Authentication authentication,
            @PathVariable @NotBlank @Pattern(regexp = PROPOSAL_ID) String proposalId,
            @Valid @RequestBody SignedReview request) {
        requireRateLimit(authentication);
        return api.fromSignedReviewResult(signedReviews.finish(
                PolicyGovernanceActor.fromOidcSession(authentication), proposalId, request));
    }

    @GetMapping("/operations/{operationId}")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.MODEL_PROMOTION_SIGNED_REVIEW_OPERATION,
            resourceType = ReadAccessResourceType.MODEL_PROMOTION_REVIEW_OPERATION,
            resourceIdVariable = "operationId")
    public ModelPromotionSignedReviewService.ReviewReceipt receipt(Authentication authentication,
            @PathVariable @NotBlank @Pattern(regexp = PROPOSAL_ID) String proposalId,
            @PathVariable @NotBlank @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String operationId) {
        requireRateLimit(authentication);
        return signedReviews.receipt(PolicyGovernanceActor.fromOidcSession(authentication),
                proposalId, operationId);
    }

    private void requireRateLimit(Authentication authentication) {
        rateLimiter.enforce(PolicyGovernanceActor.fromOidcSession(authentication).id());
    }
}
