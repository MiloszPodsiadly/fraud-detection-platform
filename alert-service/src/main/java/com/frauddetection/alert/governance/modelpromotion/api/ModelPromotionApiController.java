package com.frauddetection.alert.governance.modelpromotion.api;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.governance.modelpromotion.ModelGovernanceEnvironment;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionIntendedUse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.AssessmentResponse;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.AssessmentSelection;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.CandidateRequest;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.MutationResponse;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.ProposalRequest;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.ProposalResponse;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.RetirementProposalRequest;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.ReviewRequest;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.Selection;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.StateResponse;

@RestController
@Validated
@RequestMapping("/api/v1/governance/model-promotion")
public class ModelPromotionApiController {

    private static final String IDENTIFIER = "^[A-Za-z0-9._-]{1,96}$";
    private static final String SHA256 = "^[0-9a-f]{64}$";
    private static final String PROPOSAL_ID = "^[A-Za-z0-9._:@-]{1,128}$";

    private final ModelPromotionApiService service;
    private final ModelPromotionApiRateLimiter rateLimiter;
    private final ModelPromotionRateLimitActorKeyResolver actorKeyResolver;

    public ModelPromotionApiController(ModelPromotionApiService service, ModelPromotionApiRateLimiter rateLimiter,
            ModelPromotionRateLimitActorKeyResolver actorKeyResolver) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.actorKeyResolver = actorKeyResolver;
    }

    @GetMapping("/state")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.MODEL_PROMOTION_STATE,
            resourceType = ReadAccessResourceType.MODEL_PROMOTION,
            resourceIdParameter = "modelArtifactSha256")
    public StateResponse state(
            Authentication authentication,
            @RequestParam @NotBlank @Pattern(regexp = IDENTIFIER) String modelName,
            @RequestParam @NotBlank @Pattern(regexp = IDENTIFIER) String modelVersion,
            @RequestParam @NotBlank @Pattern(regexp = IDENTIFIER) String featureContractVersion,
            @RequestParam @NotBlank @Pattern(regexp = SHA256) String modelArtifactSha256,
            @RequestParam @NotNull ModelGovernanceEnvironment environment,
            @RequestParam @NotNull ModelPromotionIntendedUse intendedUse,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int historyLimit
    ) {
        enforceRateLimit(authentication);
        return service.state(selection(
                modelName, modelVersion, featureContractVersion, modelArtifactSha256, environment, intendedUse
        ), historyLimit);
    }

    @GetMapping("/assessment")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.MODEL_PROMOTION_ASSESSMENT,
            resourceType = ReadAccessResourceType.MODEL_PROMOTION,
            resourceIdParameter = "modelArtifactSha256")
    public AssessmentResponse assessment(
            Authentication authentication,
            @RequestParam @NotBlank @Pattern(regexp = IDENTIFIER) String modelName,
            @RequestParam @NotBlank @Pattern(regexp = IDENTIFIER) String modelVersion,
            @RequestParam @NotBlank @Pattern(regexp = IDENTIFIER) String featureContractVersion,
            @RequestParam @NotBlank @Pattern(regexp = SHA256) String modelArtifactSha256,
            @RequestParam @NotNull ModelGovernanceEnvironment environment,
            @RequestParam @NotNull ModelPromotionIntendedUse intendedUse,
            @RequestParam @NotBlank String fromInclusive,
            @RequestParam @NotBlank String toInclusive
    ) {
        enforceRateLimit(authentication);
        return service.assessment(new AssessmentSelection(
                selection(modelName, modelVersion, featureContractVersion, modelArtifactSha256, environment, intendedUse),
                fromInclusive,
                toInclusive
        ));
    }

    @PostMapping("/candidates")
    public ResponseEntity<MutationResponse> register(
            Authentication authentication,
            @Valid @RequestBody CandidateRequest request
    ) {
        enforceRateLimit(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.register(request));
    }

    @PostMapping("/proposals")
    public ResponseEntity<ProposalResponse> propose(
            Authentication authentication,
            @Valid @RequestBody ProposalRequest request
    ) {
        enforceRateLimit(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.propose(request));
    }

    @PostMapping("/retirement-proposals")
    public ResponseEntity<ProposalResponse> retire(
            Authentication authentication,
            @Valid @RequestBody RetirementProposalRequest request
    ) {
        enforceRateLimit(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.retire(request));
    }

    @PostMapping("/proposals/{proposalId}/reviews")
    public ProposalResponse review(
            Authentication authentication,
            @PathVariable @NotBlank @Pattern(regexp = PROPOSAL_ID) String proposalId,
            @Valid @RequestBody ReviewRequest request
    ) {
        enforceRateLimit(authentication);
        return service.review(proposalId, request);
    }

    private Selection selection(
            String modelName,
            String modelVersion,
            String featureContractVersion,
            String modelArtifactSha256,
            ModelGovernanceEnvironment environment,
            ModelPromotionIntendedUse intendedUse
    ) {
        return new Selection(
                modelName,
                modelVersion,
                featureContractVersion,
                modelArtifactSha256,
                environment,
                intendedUse
        );
    }

    private void enforceRateLimit(Authentication authentication) {
        rateLimiter.enforce(actorKeyResolver.resolve(authentication));
    }
}
