package com.frauddetection.alert.governance.modelpolicy;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.frauddetection.alert.governance.modelpromotion.ModelGovernanceEnvironment;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionPolicyDraft;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionPolicyValidator;
import com.frauddetection.alert.governance.modelpromotion.OnlinePolicyApproverDirectory;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

import static com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceContracts.*;

@Service
public class PolicyGovernanceService {
    private final MongoPolicyGovernanceStore store;
    private final ModelPromotionPolicyValidator validator;
    private final ObjectMapper mapper;
    private final String publisherIdentity;
    private final String publisherKeyId;
    private final String issuerIdentity;
    private final String issuerKeyId;
    private final OnlinePolicyApproverDirectory approverDirectory;

    @Autowired
    public PolicyGovernanceService(MongoPolicyGovernanceStore store, ModelPromotionPolicyValidator validator,
            ObjectMapper mapper,
            @Value("${app.model-policy-governance.publisher-identity:local-dev-policy-publisher}") String publisherIdentity,
            @Value("${app.model-policy-governance.publisher-key-id:local-dev-publisher-key-1}") String publisherKeyId,
            @Value("${app.model-policy-governance.issuer-identity:local-dev-model-promotion-issuer}") String issuerIdentity,
            @Value("${app.model-policy-governance.issuer-key-id:local-dev-model-promotion-key-1}") String issuerKeyId,
            OnlinePolicyApproverDirectory approverDirectory) {
        this.store = store;
        this.validator = validator;
        this.mapper = mapper;
        this.publisherIdentity = publisherIdentity;
        this.publisherKeyId = publisherKeyId;
        this.issuerIdentity = issuerIdentity;
        this.issuerKeyId = issuerKeyId;
        this.approverDirectory = Objects.requireNonNull(approverDirectory);
    }

    public List<PolicyProposalDocument> list(PolicyGovernanceActor actor) {
        require(actor, ModelPolicyAuthority.READ);
        return store.list(100, actor.authorities().contains(ModelPolicyAuthority.PRODUCTION));
    }

    public ProposalPage page(PolicyGovernanceActor actor, int size, String cursor,
            PolicyGovernanceState status, String policyIdPrefix) {
        require(actor, ModelPolicyAuthority.READ);
        if (size < 1 || size > 50 || policyIdPrefix != null && (policyIdPrefix.length() > 64
                || !policyIdPrefix.matches("^[A-Za-z0-9._:-]*$"))) {
            throw new IllegalArgumentException("Invalid bounded policy page request");
        }
        Instant beforeTime = null;
        String beforeId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                if (cursor.length() > 256) throw new IllegalArgumentException();
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                        .split(":", 2);
                if (parts.length != 2 || !parts[1].matches("^[A-Za-z0-9-]{1,128}$")) {
                    throw new IllegalArgumentException();
                }
                beforeTime = Instant.ofEpochMilli(Long.parseLong(parts[0]));
                beforeId = parts[1];
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Invalid policy page cursor", invalid);
            }
        }
        List<PolicyProposalDocument> rows = store.page(size + 1,
                actor.authorities().contains(ModelPolicyAuthority.PRODUCTION), status,
                policyIdPrefix, beforeTime, beforeId);
        List<PolicyProposalDocument> items = rows.stream().limit(size).toList();
        String next = rows.size() > size ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                (items.getLast().updatedAt().toEpochMilli() + ":" + items.getLast().id())
                        .getBytes(StandardCharsets.UTF_8)) : null;
        return new ProposalPage(items, next);
    }

    public HistoryPage historyPage(PolicyGovernanceActor actor, String id, int size, Long afterVersion) {
        require(actor, ModelPolicyAuthority.AUDIT_READ);
        if (size < 1 || size > 50 || afterVersion != null && afterVersion < 0) {
            throw new IllegalArgumentException("Invalid bounded history page request");
        }
        PolicyProposalDocument proposal = store.find(id);
        if (proposal == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Policy proposal not found");
        requireScope(actor, proposal.draft());
        List<PolicyGovernanceHistoryDocument> rows = store.historyPage(proposal.id(), size + 1,
                afterVersion);
        List<PolicyGovernanceHistoryDocument> items = withReviewRationales(id,
                rows.stream().limit(size).toList());
        return new HistoryPage(items, rows.size() > size ? items.getLast().version() : null);
    }

    public record ProposalPage(List<PolicyProposalDocument> items, String nextCursor) { }
    public record HistoryPage(List<PolicyGovernanceHistoryDocument> items, Long nextCursor) { }

    public PolicyProposalDocument get(PolicyGovernanceActor actor, String id) {
        require(actor, ModelPolicyAuthority.READ);
        PolicyProposalDocument proposal = store.find(id);
        if (proposal == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Policy proposal not found");
        requireScope(actor, proposal.draft());
        return proposal;
    }

    public PolicyProposalDocument operation(PolicyGovernanceActor actor, String operationId) {
        require(actor, ModelPolicyAuthority.READ);
        if (operationId == null || !operationId.matches("^[A-Za-z0-9._:-]{1,128}$")) {
            throw new IllegalArgumentException("Invalid operation ID");
        }
        PolicyGovernanceOperationDocument receipt = store.operation(operationId);
        if (receipt == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Operation not committed");
        if (!actor.id().equals(receipt.actorId())) {
            throw new AccessDeniedException("Operation receipt belongs to another actor");
        }
        requireScope(actor, receipt.result().draft());
        return receipt.result();
    }

    public List<PolicyGovernanceHistoryDocument> history(PolicyGovernanceActor actor, String id) {
        require(actor, ModelPolicyAuthority.AUDIT_READ);
        PolicyProposalDocument proposal = store.find(id);
        if (proposal == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Policy proposal not found");
        requireScope(actor, proposal.draft());
        return withReviewRationales(id, store.history(id));
    }

    private List<PolicyGovernanceHistoryDocument> withReviewRationales(String proposalId,
            List<PolicyGovernanceHistoryDocument> events) {
        Map<String, String> rationales = store.reviewRationales(proposalId, events);
        return events.stream().map(event -> event.withRationale(rationales.get(event.operationId()))).toList();
    }

    public PolicyProposalDocument create(PolicyGovernanceActor actor, DraftCommand command) {
        require(actor, ModelPolicyAuthority.CREATE);
        ModelPromotionPolicyDraft draft = draft(actor, command.draft());
        requireScope(actor, draft);
        String digest = draft.canonicalSha256(mapper);
        validateDraft(draft, digest);
        return store.create(draft, digest, actor.id(), command.operationId(),
                commandDigest("CREATE", actor.id(), draft, 0));
    }

    public PolicyProposalDocument edit(PolicyGovernanceActor actor, String id, UpdateDraftCommand command) {
        require(actor, ModelPolicyAuthority.CREATE);
        getForMutation(actor, id);
        ModelPromotionPolicyDraft draft = draft(actor, command.draft());
        String digest = draft.canonicalSha256(mapper);
        validateDraft(draft, digest);
        return store.mutate(id, command.operationId(), commandDigest("EDIT", actor.id(), draft,
                command.expectedVersion()), actor.id(), "EDIT_DRAFT", current -> {
            requireOwnerAndVersion(current, actor, command.expectedVersion(), PolicyGovernanceState.DRAFT);
            if (!current.draft().policyId().equals(draft.policyId())
                    || current.draft().revision() != draft.revision()
                    || !current.draft().governanceScope().equals(draft.governanceScope())
                    || current.draft().intendedUse() != draft.intendedUse()) {
                throw new PolicyGovernanceConflictException("Draft identity requires a new proposal revision");
            }
            return current.next(draft, digest, PolicyGovernanceState.DRAFT, null, null,
                    null, null, null, null, store.now());
        });
    }

    PolicyProposalDocument submit(PolicyGovernanceActor actor, String id, TransitionCommand command,
            PolicyWebAuthnService.VerifiedPolicyAction proof) {
        Objects.requireNonNull(proof, "Verified WebAuthn action required")
                .require(actor, id, command.operationId(), command.expectedVersion(), "POLICY_SUBMIT");
        require(actor, ModelPolicyAuthority.SUBMIT);
        getForMutation(actor, id);
        return store.mutate(id, command.operationId(), commandDigest("SUBMIT", actor.id(), id,
                command.expectedVersion()), actor.id(), "SUBMIT_DRAFT", current -> {
            requireOwnerAndVersion(current, actor, command.expectedVersion(), PolicyGovernanceState.DRAFT);
            requireFrozenDigest(current);
            Instant now = proof.acceptedAt();
            if (!now.isBefore(Instant.parse(current.draft().effectiveFromInclusive()))) {
                throw new PolicyGovernanceConflictException("Review deadline has passed");
            }
            return current.next(current.draft(), current.draftSha256(), PolicyGovernanceState.SUBMITTED,
                    actor.id(), now, null, null, null, null, now);
        });
    }

    PolicyProposalDocument review(PolicyGovernanceActor actor, String id, ReviewCommand command,
            PolicyWebAuthnService.VerifiedPolicyAction proof) {
        if (command == null || command.decision() == null) {
            throw new IllegalArgumentException("Exact policy review decision required");
        }
        Objects.requireNonNull(proof, "Verified WebAuthn action required").require(actor, id, command.operationId(), command.expectedVersion(),
                command.decision() == ReviewDecision.APPROVE ? "POLICY_APPROVE" : "POLICY_REJECT");
        require(actor, command.decision() == ReviewDecision.APPROVE
                ? ModelPolicyAuthority.APPROVE : ModelPolicyAuthority.REJECT);
        getForMutation(actor, id);
        String evidence = command.approvalEvidenceReference();
        if (evidence.length() > 256 || !evidence.matches("^[A-Za-z0-9._:/#-]+$")) {
            throw new IllegalArgumentException("Invalid approval evidence reference");
        }
        return store.mutate(id, command.operationId(), commandDigest("REVIEW", actor.id(),
                Map.of("id", id, "decision", command.decision(), "evidence", evidence),
                command.expectedVersion()), actor.id(), "REVIEW_" + command.decision(), current -> {
            requireVersionAndState(current, command.expectedVersion(), PolicyGovernanceState.SUBMITTED);
            requireFrozenDigest(current);
            if (actor.id().equals(current.authorActorId()) || actor.id().equals(current.submittingActorId())
                    || !actor.id().equals(current.draft().approverIdentity())) {
                throw new AccessDeniedException("Independent assigned policy approver required");
            }
            approverDirectory.requireEligible(actor, current.draft().governanceScope(),
                    current.draft().intendedUse());
            Instant now = proof.acceptedAt();
            if (command.decision() == ReviewDecision.APPROVE) {
                current.draft().approvedContent(now);
            }
            PolicyGovernanceState result = command.decision() == ReviewDecision.APPROVE
                    ? PolicyGovernanceState.APPROVED : PolicyGovernanceState.REJECTED;
            return current.next(current.draft(), current.draftSha256(), result,
                    current.submittingActorId(), current.submittedAt(), actor.id(), now,
                    evidence, null, now);
        });
    }

    public PolicyProposalDocument requestPublication(PolicyGovernanceActor actor, String id,
            TransitionCommand command) {
        require(actor, ModelPolicyAuthority.PUBLISH_REQUEST);
        getForMutation(actor, id);
        return store.mutate(id, command.operationId(), commandDigest("PUBLICATION_REQUEST", actor.id(), id,
                command.expectedVersion()), actor.id(), "REQUEST_PUBLICATION", current -> {
            requireVersionAndState(current, command.expectedVersion(), PolicyGovernanceState.APPROVED);
            requireFrozenDigest(current);
            if (current.reviewingActorId() == null || current.decidedAt() == null
                    || current.approvalEvidenceReference() == null
                    || current.approvalEvidenceReference().isBlank()) {
                throw new PolicyGovernanceConflictException("Approval evidence is required before publication");
            }
            store.requireVerifiedApproval(current);
            Instant now = store.now();
            return current.next(current.draft(), current.draftSha256(), PolicyGovernanceState.PUBLICATION_PENDING,
                    current.submittingActorId(), current.submittedAt(), current.reviewingActorId(),
                    current.decidedAt(), current.approvalEvidenceReference(), UUID.randomUUID().toString(), now);
        });
    }

    private ModelPromotionPolicyDraft draft(PolicyGovernanceActor actor, DraftInput input) {
        if (input == null) throw new IllegalArgumentException("Draft is required");
        if (input.intendedApproverActorId() == null
                || !input.intendedApproverActorId().matches("^oidc:[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("Assigned approver must be a stable institutional actor ID");
        }
        return new ModelPromotionPolicyDraft(ModelPromotionPolicyDraft.VERSION, input.policyId(), input.revision(),
                input.governanceScope(), input.intendedUse(), actor.id(), input.intendedApproverActorId(),
                input.approvalReference(), publisherIdentity, publisherKeyId, issuerIdentity, issuerKeyId,
                input.effectiveFromInclusive(), input.effectiveToExclusive(), input.criteria());
    }

    private void validateDraft(ModelPromotionPolicyDraft draft, String digest) {
        try {
            validator.validateNewDraftDecimals(draft);
            validator.validateDraftForPublication(draft, digest);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid policy draft semantics", invalid);
        }
    }

    private PolicyProposalDocument getForMutation(PolicyGovernanceActor actor, String id) {
        PolicyProposalDocument current = store.find(id);
        if (current == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Policy proposal not found");
        requireScope(actor, current.draft());
        return current;
    }

    private void requireOwnerAndVersion(PolicyProposalDocument current, PolicyGovernanceActor actor,
            long version, PolicyGovernanceState state) {
        requireVersionAndState(current, version, state);
        if (!actor.id().equals(current.authorActorId())) {
            throw new AccessDeniedException("Only the draft author may edit or submit it");
        }
    }

    private void requireVersionAndState(PolicyProposalDocument current, long version, PolicyGovernanceState state) {
        if (current.version() != version || current.status() != state) {
            throw new PolicyGovernanceConflictException("Policy version or state changed");
        }
    }

    private void requireFrozenDigest(PolicyProposalDocument current) {
        if (!current.draft().canonicalSha256(mapper).equals(current.draftSha256())) {
            throw new PolicyGovernanceConflictException("Persisted policy draft digest is inconsistent");
        }
    }

    private void require(PolicyGovernanceActor actor, String authority) {
        if (actor == null || !actor.authorities().contains(authority)) {
            throw new AccessDeniedException("Explicit policy mandate required: " + authority);
        }
    }

    private void requireScope(PolicyGovernanceActor actor, ModelPromotionPolicyDraft draft) {
        if (draft.governanceScope().environment() == ModelGovernanceEnvironment.PRODUCTION) {
            require(actor, ModelPolicyAuthority.PRODUCTION);
        }
    }

    private String commandDigest(String action, String actor, Object payload, long expectedVersion) {
        return GovernanceCanonicalJson.sha256(mapper, Map.of("action", action, "actor", actor,
                "payload", payload, "expectedVersion", expectedVersion));
    }
}
