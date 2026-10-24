package com.frauddetection.alert.governance.modelpromotion.api;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionEmergencyRetirementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Exceptional retirement is never selected by a review rationale string. */
@RestController
@RequestMapping("/api/v1/governance/model-promotion")
public class ModelPromotionEmergencyRetirementController {
    private static final String ID = "^[A-Za-z0-9._:@-]{1,128}$";
    private final ModelPromotionEmergencyRetirementService emergency;
    private final ModelPromotionApiService api;
    private final ModelPromotionApiRateLimiter rateLimiter;

    public ModelPromotionEmergencyRetirementController(ModelPromotionEmergencyRetirementService emergency,
            ModelPromotionApiService api, ModelPromotionApiRateLimiter rateLimiter) {
        this.emergency = emergency;
        this.api = api;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/proposals/{proposalId}/reviews/emergency-authorizations")
    public ResponseEntity<ModelPromotionEmergencyRetirementService.Summary> request(
            Authentication authentication, @PathVariable @NotBlank @Pattern(regexp = ID) String proposalId,
            @Valid @RequestBody ModelPromotionEmergencyRetirementService.Request command) {
        requireRateLimit(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(emergency.request(
                PolicyGovernanceActor.fromOidcSession(authentication), proposalId, command));
    }

    @PostMapping("/emergency-authorizations/{authorizationId}/approve")
    public ModelPromotionEmergencyRetirementService.Summary authorize(
            Authentication authentication, @PathVariable @NotBlank @Pattern(regexp = ID) String authorizationId,
            @Valid @RequestBody ModelPromotionEmergencyRetirementService.AuthorizationAttestation attestation) {
        requireRateLimit(authentication);
        return emergency.authorize(PolicyGovernanceActor.fromOidcSession(authentication),
                authorizationId, attestation);
    }

    @PostMapping("/emergency-authorizations/{authorizationId}/claim")
    public ModelPromotionEmergencyRetirementService.IssuedGrant claim(
            Authentication authentication, @PathVariable @NotBlank @Pattern(regexp = ID) String authorizationId) {
        requireRateLimit(authentication);
        return emergency.claim(PolicyGovernanceActor.fromOidcSession(authentication), authorizationId);
    }

    @GetMapping("/emergency-authorizations/active")
    @AuditedSensitiveRead
    public ModelPromotionEmergencyRetirementService.AffectedGrants affected(
            Authentication authentication, @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String incidentId) {
        requireRateLimit(authentication);
        return emergency.affected(PolicyGovernanceActor.fromOidcSession(authentication),
                new ModelPromotionEmergencyRetirementService.AffectedSelector(actorId, incidentId));
    }

    @PostMapping("/emergency-authorizations/active/revoke")
    public ModelPromotionEmergencyRetirementService.AffectedGrants revokeAffected(
            Authentication authentication,
            @Valid @RequestBody ModelPromotionEmergencyRetirementService.AffectedSelector selector) {
        requireRateLimit(authentication);
        return emergency.revokeAffected(PolicyGovernanceActor.fromOidcSession(authentication), selector);
    }

    @GetMapping("/emergency-authorizations/{authorizationId}")
    @AuditedSensitiveRead
    public ModelPromotionEmergencyRetirementService.AuthorizationReviewView inspect(
            Authentication authentication, @PathVariable @NotBlank @Pattern(regexp = ID) String authorizationId) {
        requireRateLimit(authentication);
        return emergency.inspect(PolicyGovernanceActor.fromOidcSession(authentication), authorizationId);
    }

    @PostMapping("/emergency-authorizations/{authorizationId}/revoke")
    public ModelPromotionEmergencyRetirementService.Summary revoke(
            Authentication authentication, @PathVariable @NotBlank @Pattern(regexp = ID) String authorizationId) {
        requireRateLimit(authentication);
        return emergency.revoke(PolicyGovernanceActor.fromOidcSession(authentication), authorizationId);
    }

    @PostMapping("/proposals/{proposalId}/reviews/emergency")
    public ModelPromotionApiContracts.ProposalResponse retire(
            Authentication authentication, @PathVariable @NotBlank @Pattern(regexp = ID) String proposalId,
            @Valid @RequestBody ModelPromotionEmergencyRetirementService.Consume command) {
        requireRateLimit(authentication);
        return api.fromSignedReviewResult(emergency.retire(PolicyGovernanceActor.fromOidcSession(authentication),
                proposalId, command.authorizationId(), command.token()));
    }

    @GetMapping("/emergency-authorizations/{authorizationId}/audit")
    @AuditedSensitiveRead
    public ModelPromotionEmergencyRetirementService.AuditView audit(
            Authentication authentication, @PathVariable @NotBlank @Pattern(regexp = ID) String authorizationId) {
        requireRateLimit(authentication);
        return emergency.auditView(PolicyGovernanceActor.fromOidcSession(authentication), authorizationId);
    }

    private void requireRateLimit(Authentication authentication) {
        if (authentication == null || !rateLimiter.allow(authentication.getName())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Model promotion API rate limit exceeded.");
        }
    }
}
