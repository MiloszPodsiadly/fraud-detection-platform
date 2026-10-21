package com.frauddetection.alert.governance.modelpolicy;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceContracts.*;

@RestController
@Validated
@RequestMapping("/api/v1/governance/model-policies")
public class PolicyGovernanceController {
    private static final String ID = "^[A-Za-z0-9-]{1,128}$";
    private final PolicyGovernanceService service;
    private final PolicyWebAuthnService webAuthn;
    private final MongoTemplate mongo;

    public PolicyGovernanceController(PolicyGovernanceService service, PolicyWebAuthnService webAuthn,
            MongoTemplate mongo) {
        this.service = service;
        this.webAuthn = webAuthn;
        this.mongo = mongo;
    }

    @GetMapping
    public List<PolicyProposalDocument> list(Authentication authentication) {
        return service.list(PolicyGovernanceActor.from(authentication));
    }

    @GetMapping("/me")
    public ActorCapabilities me(Authentication authentication) {
        PolicyGovernanceActor actor = PolicyGovernanceActor.from(authentication);
        return new ActorCapabilities(actor.id(), actor.authorities().stream()
                .filter(authority -> authority.startsWith("model-policy:"))
                .sorted().toList());
    }

    public record ActorCapabilities(String actorId, List<String> authorities) { }

    @GetMapping("/{id}")
    public PolicyProposalDocument get(Authentication authentication,
            @PathVariable @NotBlank @Pattern(regexp = ID) String id) {
        return service.get(PolicyGovernanceActor.from(authentication), id);
    }

    @GetMapping("/{id}/history")
    public List<PolicyGovernanceHistoryDocument> history(Authentication authentication,
            @PathVariable @NotBlank @Pattern(regexp = ID) String id) {
        return service.history(PolicyGovernanceActor.from(authentication), id).stream().map(event -> {
            if (!"REVIEW_APPROVE".equals(event.action()) && !"REVIEW_REJECT".equals(event.action())) {
                return event;
            }
            PolicyWebAuthnChallenge challenge = mongo.findById("action:" + event.operationId(),
                    PolicyWebAuthnChallenge.class);
            return event.withRationale(challenge != null && challenge.used()
                    && id.equals(challenge.proposalId())
                    && event.actorId().equals(challenge.actorId())
                    && event.draftSha256().equals(challenge.draftSha256())
                    && event.operationId().equals(challenge.operationId())
                    ? challenge.rationale() : null);
        }).toList();
    }

    @PostMapping("/drafts")
    public ResponseEntity<PolicyProposalDocument> create(Authentication authentication,
            @Valid @RequestBody DraftCommand request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(PolicyGovernanceActor.from(authentication), request));
    }

    @PutMapping("/drafts/{id}")
    public PolicyProposalDocument edit(Authentication authentication,
            @PathVariable @NotBlank @Pattern(regexp = ID) String id,
            @Valid @RequestBody UpdateDraftCommand request) {
        return service.edit(PolicyGovernanceActor.from(authentication), id, request);
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
    public PolicyWebAuthnService.CeremonyOptions startRegistration(Authentication authentication) {
        return webAuthn.startRegistration(PolicyGovernanceActor.fromOidcSession(authentication));
    }

    @PostMapping("/webauthn/registrations/complete")
    public PolicyWebAuthnService.CredentialSummary finishRegistration(Authentication authentication,
            @RequestBody PolicyWebAuthnService.FinishCeremony request) {
        return webAuthn.finishRegistration(PolicyGovernanceActor.fromOidcSession(authentication), request);
    }

    @GetMapping("/webauthn/credentials")
    public List<PolicyWebAuthnService.CredentialSummary> credentials(Authentication authentication) {
        return webAuthn.credentials(PolicyGovernanceActor.fromOidcSession(authentication));
    }

    @DeleteMapping("/webauthn/credentials/{credentialId}")
    public ResponseEntity<Void> revokeCredential(Authentication authentication,
            @PathVariable String credentialId) {
        webAuthn.revoke(PolicyGovernanceActor.fromOidcSession(authentication), credentialId);
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
