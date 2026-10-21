package com.frauddetection.alert.governance.modelpolicy;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal maker-checker authorization; the bearer grant is returned once to the reviewer. */
@RestController
@RequestMapping("/api/v1/governance/model-policies/webauthn/enrollment-grants")
public class PolicyCredentialEnrollmentGrantController {
    private final PolicyCredentialEnrollmentGrantService grants;

    public PolicyCredentialEnrollmentGrantController(PolicyCredentialEnrollmentGrantService grants) {
        this.grants = grants;
    }

    @PostMapping
    public ResponseEntity<PolicyCredentialEnrollmentGrantService.GrantRequestSummary> request(
            Authentication authentication, @RequestBody PolicyCredentialEnrollmentGrantService.GrantRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(grants.request(PolicyGovernanceActor.fromOidcSession(authentication), body));
    }

    @PostMapping("/{id}/approvals")
    public PolicyCredentialEnrollmentGrantService.IssuedGrant approve(Authentication authentication,
            @PathVariable String id) {
        return grants.approve(PolicyGovernanceActor.fromOidcSession(authentication), id);
    }
}
