package com.frauddetection.alert.governance.audit;

import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxRecoveryService;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/governance/audit")
public class GovernanceEventAuditRecoveryController {
    private final WriteActionAuditOutboxRecoveryService recovery;

    public GovernanceEventAuditRecoveryController(WriteActionAuditOutboxRecoveryService recovery) {
        this.recovery = recovery;
    }

    @GetMapping("/recovery")
    public List<WriteActionAuditOutboxRecoveryService.RecoveryView> list(Authentication authentication) {
        return recovery.list(PolicyGovernanceActor.from(authentication));
    }

    @GetMapping("/recovery/history")
    public List<WriteActionAuditOutboxRecoveryService.RecoveryView> history(Authentication authentication) {
        return recovery.history(PolicyGovernanceActor.from(authentication));
    }

    @PostMapping("/commitments/{kind}/{eventId}/recovery")
    public WriteActionAuditOutboxRecoveryService.RecoveryView recover(Authentication authentication,
            @PathVariable String kind, @PathVariable String eventId,
            @RequestBody WriteActionAuditOutboxRecoveryService.RecoveryCommand command) {
        return recovery.recover(PolicyGovernanceActor.from(authentication), kind, eventId, command);
    }
}
