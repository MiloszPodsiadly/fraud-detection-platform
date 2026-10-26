package com.frauddetection.alert.governance.modelpolicy;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Arrays;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionCriterion;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionPolicyValidator;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import org.springframework.security.access.AccessDeniedException;

import static com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceContracts.*;

@RestController
@Validated
@RequestMapping("/api/v1/governance/model-policies")
public class PolicyGovernanceController {
    private static final String ID = "^[A-Za-z0-9-]{1,128}$";
    private final PolicyGovernanceService service;
    private final PolicyWebAuthnService webAuthn;
    private final ObjectMapper mapper;
    private final Validator beanValidator;

    public PolicyGovernanceController(PolicyGovernanceService service, PolicyWebAuthnService webAuthn,
            ObjectMapper mapper, Validator beanValidator) {
        this.service = service;
        this.webAuthn = webAuthn;
        this.mapper = mapper;
        this.beanValidator = beanValidator;
    }

    @GetMapping
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.MODEL_POLICY_LIST,
            resourceType = ReadAccessResourceType.MODEL_POLICY)
    public List<PolicyProposalDocument> list(Authentication authentication) {
        return service.list(PolicyGovernanceActor.from(authentication));
    }

    @GetMapping("/page")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.MODEL_POLICY_PAGE,
            resourceType = ReadAccessResourceType.MODEL_POLICY)
    public PolicyGovernanceService.ProposalPage page(Authentication authentication,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) PolicyGovernanceState status,
            @RequestParam(required = false) String policyIdPrefix) {
        return service.page(PolicyGovernanceActor.from(authentication), size, cursor, status, policyIdPrefix);
    }

    @GetMapping("/me")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.MODEL_POLICY_ACTOR_CAPABILITIES,
            resourceType = ReadAccessResourceType.POLICY_ACTOR)
    public ActorCapabilities me(Authentication authentication) {
        PolicyGovernanceActor actor = PolicyGovernanceActor.from(authentication);
        return new ActorCapabilities(actor.id(), actor.authorities().stream()
                .filter(authority -> authority.startsWith("model-policy:"))
                .sorted().toList());
    }

    public record ActorCapabilities(String actorId, List<String> authorities) { }

    @GetMapping("/schema")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.MODEL_POLICY_SCHEMA,
            resourceType = ReadAccessResourceType.MODEL_POLICY)
    public List<CriterionSchema> schema(Authentication authentication) {
        var actor = PolicyGovernanceActor.from(authentication);
        if (!actor.authorities().contains(ModelPolicyAuthority.READ)) {
            throw new AccessDeniedException("Policy read mandate required");
        }
        return Arrays.stream(ModelPromotionCriterion.values()).map(criterion -> new CriterionSchema(
                criterion.name(), criterion.evidenceSource(), criterion.metricSemantics(),
                criterion.populationDefinition(), criterion.denominatorDefinition(),
                criterion.requiredThresholdKeys().stream().sorted().toList(),
                criterion.requiredThresholdKeys().stream().sorted()
                        .map(ModelPromotionPolicyValidator::describeThreshold).toList(),
                ModelPromotionPolicyValidator.isNonWaivable(criterion))).toList();
    }

    public record CriterionSchema(String criterion, String evidenceSource, String metricSemantics,
            String populationDefinition, String denominatorDefinition,
            List<String> requiredThresholdKeys,
            List<ModelPromotionPolicyValidator.ThresholdDescription> thresholds,
            boolean nonWaivable) { }

    @GetMapping("/operations/{operationId}")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.MODEL_POLICY_OPERATION,
            resourceType = ReadAccessResourceType.MODEL_POLICY, resourceIdVariable = "operationId")
    public PolicyProposalDocument operation(Authentication authentication, @PathVariable String operationId) {
        return service.operation(PolicyGovernanceActor.from(authentication), operationId);
    }

    @GetMapping("/{id}")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.MODEL_POLICY_DETAIL,
            resourceType = ReadAccessResourceType.MODEL_POLICY, resourceIdVariable = "id")
    public PolicyProposalDocument get(Authentication authentication,
            @PathVariable @NotBlank @Pattern(regexp = ID) String id) {
        return service.get(PolicyGovernanceActor.from(authentication), id);
    }

    @GetMapping("/{id}/history")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.MODEL_POLICY_HISTORY,
            resourceType = ReadAccessResourceType.MODEL_POLICY, resourceIdVariable = "id")
    public List<PolicyGovernanceHistoryDocument> history(Authentication authentication,
            @PathVariable @NotBlank @Pattern(regexp = ID) String id) {
        return service.history(PolicyGovernanceActor.from(authentication), id);
    }

    @GetMapping("/{id}/history/page")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.MODEL_POLICY_HISTORY_PAGE,
            resourceType = ReadAccessResourceType.MODEL_POLICY, resourceIdVariable = "id")
    public PolicyGovernanceService.HistoryPage historyPage(Authentication authentication,
            @PathVariable @NotBlank @Pattern(regexp = ID) String id,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) Long afterVersion) {
        return service.historyPage(PolicyGovernanceActor.from(authentication), id, size, afterVersion);
    }

    @PostMapping("/drafts")
    public ResponseEntity<PolicyProposalDocument> create(Authentication authentication,
            @RequestBody JsonNode body) {
        DraftCommand request = exactDraftCommand(body, DraftCommand.class);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(PolicyGovernanceActor.from(authentication), request));
    }

    @PutMapping("/drafts/{id}")
    public PolicyProposalDocument edit(Authentication authentication,
            @PathVariable @NotBlank @Pattern(regexp = ID) String id,
            @RequestBody JsonNode body) {
        UpdateDraftCommand request = exactDraftCommand(body, UpdateDraftCommand.class);
        return service.edit(PolicyGovernanceActor.from(authentication), id, request);
    }

    private <T> T exactDraftCommand(JsonNode body, Class<T> type) {
        ExactPolicyDecimalContract.requireDraftTransport(body);
        try {
            T command = mapper.treeToValue(body, type);
            var violations = beanValidator.validate(command);
            if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
            return command;
        } catch (tools.jackson.core.JacksonException invalid) {
            throw new IllegalArgumentException("Invalid exact policy draft request", invalid);
        }
    }

    @PostMapping("/{id}/submit")
    public PolicyProposalDocument submit(Authentication authentication,
            @PathVariable @NotBlank @Pattern(regexp = ID) String id,
            @Valid @RequestBody PolicyWebAuthnService.FinishAction request) {
        return webAuthn.finishAction(PolicyGovernanceActor.fromOidcSession(authentication), id, request, "submit");
    }

    @PostMapping("/{id}/review")
    public PolicyProposalDocument review(Authentication authentication,
            @PathVariable @NotBlank @Pattern(regexp = ID) String id,
            @Valid @RequestBody PolicyWebAuthnService.FinishAction request) {
        return webAuthn.finishAction(PolicyGovernanceActor.fromOidcSession(authentication), id, request, "review");
    }

    @PostMapping("/webauthn/registrations")
    public PolicyWebAuthnService.CeremonyOptions startRegistration(Authentication authentication,
            @RequestBody PolicyWebAuthnService.RegistrationIntent request) {
        return webAuthn.startRegistration(PolicyGovernanceActor.fromOidcSession(authentication), request);
    }

    @PostMapping("/webauthn/registrations/complete")
    public PolicyWebAuthnService.CredentialSummary finishRegistration(Authentication authentication,
            @RequestBody PolicyWebAuthnService.FinishCeremony request) {
        return webAuthn.finishRegistration(PolicyGovernanceActor.fromOidcSession(authentication), request);
    }

    @GetMapping("/webauthn/credentials")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.MODEL_POLICY_CREDENTIALS,
            resourceType = ReadAccessResourceType.POLICY_ACTOR)
    public List<PolicyWebAuthnService.CredentialSummary> credentials(Authentication authentication) {
        return webAuthn.credentials(PolicyGovernanceActor.fromOidcSession(authentication));
    }

    @GetMapping("/webauthn/security-events")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.MODEL_POLICY_CREDENTIAL_SECURITY_EVENTS,
            resourceType = ReadAccessResourceType.POLICY_ACTOR)
    public List<PolicyCredentialSecurityEventDocument> credentialSecurityEvents(Authentication authentication) {
        return webAuthn.securityEvents(PolicyGovernanceActor.fromOidcSession(authentication));
    }

    @PutMapping("/webauthn/credentials/{credentialId}/label")
    public PolicyWebAuthnService.CredentialSummary renameCredential(Authentication authentication,
            @PathVariable String credentialId, @RequestBody CredentialLabel request) {
        return webAuthn.renameCredential(PolicyGovernanceActor.fromOidcSession(authentication),
                credentialId, request.label());
    }

    public record CredentialLabel(String label) { }

    @PostMapping("/webauthn/credentials/{credentialId}/revocation-challenges")
    public PolicyWebAuthnService.CeremonyOptions startCredentialRevocation(Authentication authentication,
            @PathVariable String credentialId,
            @RequestBody PolicyWebAuthnService.CredentialActionIntent request) {
        return webAuthn.startCredentialRevocation(PolicyGovernanceActor.fromOidcSession(authentication),
                credentialId, request);
    }

    @PostMapping("/webauthn/credentials/{credentialId}/revoke")
    public ResponseEntity<Void> revokeCredential(Authentication authentication,
            @PathVariable String credentialId, @RequestBody PolicyWebAuthnService.FinishAction request) {
        webAuthn.finishCredentialRevocation(PolicyGovernanceActor.fromOidcSession(authentication),
                credentialId, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/webauthn/credentials/{credentialId}/protected-revoke")
    public ResponseEntity<Void> protectedCredentialRevocation(Authentication authentication,
            @PathVariable String credentialId, @RequestBody PolicyWebAuthnService.ProtectedRevocation request) {
        webAuthn.protectedCredentialRevocation(PolicyGovernanceActor.fromOidcSession(authentication),
                credentialId, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/submit/challenges")
    public PolicyWebAuthnService.CeremonyOptions submitChallenge(Authentication authentication,
            @PathVariable @NotBlank @Pattern(regexp = ID) String id,
            @RequestBody PolicyWebAuthnService.ActionIntent request) {
        return webAuthn.startAction(PolicyGovernanceActor.fromOidcSession(authentication), id, request, "submit");
    }

    @PostMapping("/{id}/review/challenges")
    public PolicyWebAuthnService.CeremonyOptions reviewChallenge(Authentication authentication,
            @PathVariable @NotBlank @Pattern(regexp = ID) String id,
            @RequestBody PolicyWebAuthnService.ActionIntent request) {
        return webAuthn.startAction(PolicyGovernanceActor.fromOidcSession(authentication), id, request, "review");
    }

    @PostMapping("/{id}/publication-requests")
    public PolicyProposalDocument requestPublication(Authentication authentication,
            @PathVariable @NotBlank @Pattern(regexp = ID) String id,
            @Valid @RequestBody TransitionCommand request) {
        return service.requestPublication(PolicyGovernanceActor.from(authentication), id, request);
    }
}
