package com.frauddetection.alert.governance.modelpolicy;

import com.frauddetection.alert.governance.modelpromotion.PolicyPublicationRecoveryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/governance/model-policies/{id}/publication/recovery")
public class PolicyPublicationRecoveryController {
    private final PolicyPublicationRecoveryService recovery;

    public PolicyPublicationRecoveryController(PolicyPublicationRecoveryService recovery) {
        this.recovery = recovery;
    }

    @GetMapping
    public PolicyPublicationRecoveryService.Diagnostics inspect(Authentication authentication,
            @PathVariable String id) {
        return recovery.inspect(PolicyGovernanceActor.from(authentication), id);
    }

    @PostMapping
    public PolicyPublicationRecoveryService.Diagnostics request(Authentication authentication,
            @PathVariable String id, @Valid @RequestBody RecoveryRequest request) {
        return recovery.request(PolicyGovernanceActor.fromOidcSession(authentication), id, request.operationId());
    }

    public record RecoveryRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$")
            String operationId) { }
}
