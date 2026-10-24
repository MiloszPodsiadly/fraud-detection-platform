package com.frauddetection.alert.governance.audit;

import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/governance/audit/commitments")
public class GovernanceEventVerificationController {
    private final GovernanceEventVerificationService verification;

    public GovernanceEventVerificationController(GovernanceEventVerificationService verification) {
        this.verification = verification;
    }

    @GetMapping("/{kind}/{eventId}")
    public GovernanceEventVerificationService.Verification verify(Authentication authentication,
            @PathVariable GovernanceEventCommitment.Kind kind, @PathVariable String eventId) {
        return verification.verify(PolicyGovernanceActor.from(authentication), kind, eventId);
    }
}
