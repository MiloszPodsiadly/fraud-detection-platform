package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/governance/policy-trust")
public class PolicyTrustAdministrationController {
    private final PolicyTrustRegistrySnapshotService registries;
    private final PolicyTrustChangeService changes;

    public PolicyTrustAdministrationController(PolicyTrustRegistrySnapshotService registries,
            PolicyTrustChangeService changes) {
        this.registries = registries;
        this.changes = changes;
    }

    @GetMapping("/registries")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.POLICY_TRUST_REGISTRY_LIST,
            resourceType = ReadAccessResourceType.POLICY_TRUST)
    public List<PolicyTrustRegistrySnapshotService.RegistryView> registries(Authentication authentication) {
        return registries.inspect(PolicyGovernanceActor.from(authentication));
    }

    @GetMapping("/requests")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.POLICY_TRUST_REQUEST_LIST,
            resourceType = ReadAccessResourceType.POLICY_TRUST)
    public List<PolicyTrustChangeDocument> requests(Authentication authentication) {
        return changes.recent(PolicyGovernanceActor.from(authentication));
    }

    @GetMapping("/requests/{id}/audit")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.POLICY_TRUST_REQUEST_AUDIT,
            resourceType = ReadAccessResourceType.POLICY_TRUST, resourceIdVariable = "id")
    public List<PolicyTrustChangeAuditDocument> audit(Authentication authentication, @PathVariable String id) {
        return changes.audit(PolicyGovernanceActor.from(authentication), id);
    }

    @GetMapping("/requests/{id}/deployment")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.POLICY_TRUST_REQUEST_DEPLOYMENT,
            resourceType = ReadAccessResourceType.POLICY_TRUST, resourceIdVariable = "id")
    public PolicyTrustChangeService.DeploymentView deployment(Authentication authentication,
            @PathVariable String id) {
        return changes.deployment(PolicyGovernanceActor.from(authentication), id);
    }

    @GetMapping("/requests/{id}/candidate")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.POLICY_TRUST_CANDIDATE,
            resourceType = ReadAccessResourceType.POLICY_TRUST, resourceIdVariable = "id")
    public PolicyTrustChangeService.CandidateView candidate(Authentication authentication,
            @PathVariable String id) {
        return changes.candidate(PolicyGovernanceActor.from(authentication), id);
    }

    @GetMapping("/requests/{id}/installation")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.POLICY_TRUST_INSTALLATION,
            resourceType = ReadAccessResourceType.POLICY_TRUST, resourceIdVariable = "id")
    public PolicyTrustChangeService.InstallationBundle installation(Authentication authentication,
            @PathVariable String id) {
        return changes.installation(PolicyGovernanceActor.from(authentication), id);
    }

    @PostMapping("/requests/preview")
    public PolicyTrustChangeService.CandidatePreview preview(Authentication authentication,
            @RequestBody PolicyTrustChangeService.Intent intent) {
        return changes.preview(PolicyGovernanceActor.fromOidcSession(authentication), intent);
    }

    @PostMapping("/requests/challenges")
    public PolicyWebAuthnService.CeremonyOptions requestChallenge(Authentication authentication,
            @RequestBody StartRequest request) {
        return changes.startRequest(PolicyGovernanceActor.fromOidcSession(authentication),
                request.operationId(), request.intent());
    }

    @PostMapping("/requests")
    public PolicyTrustChangeDocument request(Authentication authentication,
            @RequestBody FinishRequest request) {
        return changes.finishRequest(PolicyGovernanceActor.fromOidcSession(authentication),
                request.operationId(), request.intent(), request.challengeId(), request.responseJson());
    }

    @PostMapping("/requests/{id}/reviews/challenges")
    public PolicyWebAuthnService.CeremonyOptions reviewChallenge(Authentication authentication,
            @PathVariable String id, @RequestBody StartReview request) {
        return changes.startReview(PolicyGovernanceActor.fromOidcSession(authentication),
                id, request.operationId(), request.decision());
    }

    @PostMapping("/requests/{id}/reviews")
    public PolicyTrustChangeDocument review(Authentication authentication,
            @PathVariable String id, @RequestBody FinishReview request) {
        return changes.finishReview(PolicyGovernanceActor.fromOidcSession(authentication),
                id, request.decision(), request.challengeId(), request.responseJson());
    }

    public record StartRequest(String operationId, PolicyTrustChangeService.Intent intent) { }
    public record FinishRequest(String operationId, PolicyTrustChangeService.Intent intent,
            String challengeId, String responseJson) { }
    public record StartReview(String operationId, PolicyTrustChangeService.Decision decision) { }
    public record FinishReview(PolicyTrustChangeService.Decision decision,
            String challengeId, String responseJson) { }
}
