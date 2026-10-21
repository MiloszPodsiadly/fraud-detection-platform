package com.frauddetection.alert.governance.modelpolicy;

import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/governance/policy-security")
public class PolicySecurityActorController {
    @GetMapping("/me")
    public Capabilities me(Authentication authentication) {
        PolicyGovernanceActor actor = PolicyGovernanceActor.from(authentication);
        List<String> authorities = actor.authorities().stream()
                .filter(authority -> authority.startsWith("model-policy:"))
                .sorted().toList();
        if (authorities.stream().noneMatch(authority ->
                authority.equals(ModelPolicyAuthority.CREDENTIAL_ENROLL)
                        || authority.equals(ModelPolicyAuthority.CREDENTIAL_ADMIN)
                        || authority.equals(ModelPolicyAuthority.TRUST_READ)
                        || authority.equals(ModelPolicyAuthority.TRUST_REQUEST)
                        || authority.equals(ModelPolicyAuthority.TRUST_APPROVE)
                        || authority.equals(ModelPolicyAuthority.PUBLICATION_RECOVER))) {
            throw new AccessDeniedException("Policy security workspace mandate required");
        }
        return new Capabilities(actor.id(), actor.issuer(), actor.subject(), authorities);
    }

    public record Capabilities(String actorId, String issuer, String subject,
            List<String> authorities) { }
}
