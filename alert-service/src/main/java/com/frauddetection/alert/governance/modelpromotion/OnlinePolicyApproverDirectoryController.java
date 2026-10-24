package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/governance/model-policies/approvers")
public class OnlinePolicyApproverDirectoryController {
    private final OnlinePolicyApproverDirectory directory;

    public OnlinePolicyApproverDirectoryController(OnlinePolicyApproverDirectory directory) {
        this.directory = directory;
    }

    @GetMapping
    public List<OnlinePolicyApproverDirectory.Entry> eligible(Authentication authentication,
            @RequestParam ModelGovernanceEnvironment environment,
            @RequestParam ModelPromotionIntendedUse intendedUse) {
        return directory.eligible(PolicyGovernanceActor.from(authentication),
                new ModelGovernanceScope(ModelGovernanceScope.CURRENT_INSTITUTION, environment), intendedUse);
    }
}
