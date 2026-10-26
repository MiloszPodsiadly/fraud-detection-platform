package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.governance.modelpolicy.GovernanceBoundWebAuthnProof;
import com.frauddetection.alert.governance.modelpolicy.GovernanceBoundWebAuthnProofVerifier;
import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnService;
import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.time.Instant;

/** Model-promotion contract builder; assertion verification remains in the shared institutional WebAuthn service. */
@Service
public class ModelPromotionSignedReviewService {
    private static final String NAMESPACE = "model-promotion-review";
    private static final String DOMAIN = "com.frauddetection.model-promotion.review.v1";
    private final MongoModelPromotionStore store;
    private final ModelPromotionAuthorization authorization;
    private final ModelPromotionWorkflowService workflow;
    private final PolicyWebAuthnService webAuthn;
    private final MongoTemplate mongo;
    private final GovernanceBoundWebAuthnProofVerifier historicalVerifier;

    public ModelPromotionSignedReviewService(MongoModelPromotionStore store,
            ModelPromotionAuthorization authorization, ModelPromotionWorkflowService workflow,
            PolicyWebAuthnService webAuthn, MongoTemplate mongo, ObjectMapper mapper) {
        this.store = store;
        this.authorization = authorization;
        this.workflow = workflow;
        this.webAuthn = webAuthn;
        this.mongo = mongo;
        this.historicalVerifier = new GovernanceBoundWebAuthnProofVerifier(mapper);
    }

    public PolicyWebAuthnService.CeremonyOptions start(PolicyGovernanceActor actor, String proposalId,
            ReviewIntent intent) {
        ModelPromotionProposalDocument proposal = checkedProposal(actor, proposalId, intent);
        if (proposal.reviewStatus() != ModelPromotionProposalDocument.ReviewStatus.PENDING) {
            throw new ModelPromotionPersistenceConflictException("Model promotion proposal was already reviewed");
        }
        return webAuthn.startBoundAssertion(actor, NAMESPACE, intent.operationId(), contract(proposal, intent));
    }

    public ModelPromotionProposalResult finish(PolicyGovernanceActor actor, String proposalId,
            SignedReview command) {
        if (command == null || command.intent() == null || command.challengeId() == null
                || command.responseJson() == null || !command.challengeId().equals(NAMESPACE + ":"
                + command.intent().operationId())) {
            throw new IllegalArgumentException("Complete signed model promotion review required");
        }
        var existing = mongo.findById(command.challengeId(), ModelPromotionWebAuthnReviewEvidence.class);
        if (existing != null) return recover(actor, proposalId, command, existing);
        ModelPromotionProposalDocument proposal = checkedProposal(actor, proposalId, command.intent());
        Map<String, Object> exactContract = contract(proposal, command.intent());
        var intent = command.intent();
        var request = new ModelPromotionWorkflowService.ReviewRequest(proposalId, intent.approvalId(),
                intent.outcome(), intent.operationId(), intent.rationale(), intent.rationaleSha256());
        ModelPromotionProposalResult result = workflow.reviewProposal(request, () -> {
            var proof = webAuthn.acceptBoundAssertion(actor, NAMESPACE, command.challengeId(),
                    command.responseJson(), exactContract);
            mongo.insert(new ModelPromotionWebAuthnReviewEvidence(command.challengeId(), proposalId,
                    intent.operationId(), intent.approvalId(), intent.outcome(), intent.rationaleSha256(),
                    actor.id(), actor.issuer(), actor.subject(), proof.credentialId(),
                    proof.contractSha256(), proof.assertionSha256(), proof.signatureCount(), proof.acceptedAt()));
        });
        var accepted = mongo.findById(command.challengeId(), ModelPromotionWebAuthnReviewEvidence.class);
        if (accepted == null) throw new ModelPromotionPersistenceConflictException("Signed review evidence absent");
        verifyReplay(actor, proposalId, command, accepted);
        return result;
    }

    /** Actor-scoped durable receipt lookup without retaining or replaying a browser assertion. */
    public ReviewReceipt receipt(PolicyGovernanceActor actor, String proposalId, String operationId) {
        if (actor == null || operationId == null || !operationId.matches("^[A-Za-z0-9._:-]{1,128}$"))
            throw new AccessDeniedException("Institutional OIDC reviewer required");
        var proposal = store.findProposal(proposalId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Review operation unavailable"));
        if (!operationId.equals(proposal.reviewOperationId()) || proposal.approval() == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Review operation unavailable");
        String mandate = proposal.approval().outcome() == ModelPromotionApproval.Outcome.APPROVED
                ? AnalystAuthority.MODEL_PROMOTION_APPROVE : AnalystAuthority.MODEL_PROMOTION_REJECT;
        boolean legacyReview = proposal.reviewerIdentity() == null;
        if (!actor.id().equals(authorization.requireActor(mandate,
                proposal.transition().governanceScope()))
                || (legacyReview ? !actor.subject().equals(proposal.approval().approvedBy())
                        : !actor.id().equals(proposal.approval().approvedBy())
                                || !actor.issuer().equals(proposal.reviewerIdentity().issuer())
                                || !actor.subject().equals(proposal.reviewerIdentity().subject())))
            throw new AccessDeniedException("Review receipt belongs to a different actor or scope");
        var evidence = mongo.findById(NAMESPACE + ":" + operationId,
                ModelPromotionWebAuthnReviewEvidence.class);
        if (evidence == null || !proposalId.equals(evidence.proposalId())
                || !operationId.equals(evidence.operationId())
                || !actor.id().equals(evidence.actorId())
                || !actor.issuer().equals(evidence.issuer())
                || !actor.subject().equals(evidence.subject())
                || !proposal.approval().approvalId().equals(evidence.approvalId())
                || proposal.approval().outcome() != evidence.outcome()
                || !proposal.approval().rationale().sha256().equals(evidence.rationaleSha256()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Review operation unavailable");
        return new ReviewReceipt(proposalId, operationId, evidence.approvalId(),
                evidence.outcome(), evidence.rationaleSha256(), evidence.acceptedAt());
    }

    public record ReviewReceipt(String proposalId, String operationId, String approvalId,
            ModelPromotionApproval.Outcome outcome, String rationaleSha256, Instant committedAt) { }

    private ModelPromotionProposalResult recover(PolicyGovernanceActor actor, String proposalId,
            SignedReview command, ModelPromotionWebAuthnReviewEvidence evidence) {
        verifyReplay(actor, proposalId, command, evidence);
        var proposal = checkedProposal(actor, proposalId, command.intent());
        if ((proposal.reviewStatus() != ModelPromotionProposalDocument.ReviewStatus.APPROVED
                && proposal.reviewStatus() != ModelPromotionProposalDocument.ReviewStatus.REJECTED)
                || !command.intent().operationId().equals(proposal.reviewOperationId())
                || proposal.approval() == null
                || !command.intent().approvalId().equals(proposal.approval().approvalId())
                || command.intent().outcome() != proposal.approval().outcome()
                || !command.intent().rationaleSha256().equals(proposal.approval().rationale().sha256())) {
            throw new ModelPromotionPersistenceConflictException("Committed review receipt unavailable");
        }
        return proposal.toResult(true);
    }

    private void verifyReplay(PolicyGovernanceActor actor, String proposalId, SignedReview command,
            ModelPromotionWebAuthnReviewEvidence evidence) {
        var intent = command.intent();
        if (!actor.id().equals(evidence.actorId()) || !actor.issuer().equals(evidence.issuer())
                || !actor.subject().equals(evidence.subject())
                || !proposalId.equals(evidence.proposalId())
                || !intent.operationId().equals(evidence.operationId())
                || !intent.approvalId().equals(evidence.approvalId())
                || intent.outcome() != evidence.outcome()
                || !intent.rationaleSha256().equals(evidence.rationaleSha256())
                || !ModelPromotionDecisionRationale.sha256(command.responseJson())
                        .equals(evidence.assertionSha256())) {
            throw new AccessDeniedException("Signed review replay differs from accepted assertion");
        }
    }

    /** Audit-only historical signature check; current reviewer mandates are assessed separately. */
    public void verifyHistoricalReview(PolicyGovernanceActor auditor, String proposalId) {
        if (auditor == null || !auditor.authorities().contains(ModelPolicyAuthority.AUDIT_READ)) {
            throw new AccessDeniedException("Dedicated policy audit mandate required");
        }
        ModelPromotionProposalDocument proposal = store.findProposal(proposalId).orElseThrow(() ->
                new ModelPromotionPersistenceConflictException("Committed review unavailable"));
        if (proposal.transition().governanceScope().environment() == ModelGovernanceEnvironment.PRODUCTION
                && !auditor.authorities().contains(ModelPolicyAuthority.PRODUCTION)) {
            throw new AccessDeniedException("Production policy audit scope required");
        }
        ModelPromotionApproval approval = proposal.approval();
        if (approval == null || proposal.reviewOperationId() == null
                || proposal.reviewStatus() != (approval.outcome() == ModelPromotionApproval.Outcome.APPROVED
                        ? ModelPromotionProposalDocument.ReviewStatus.APPROVED
                        : ModelPromotionProposalDocument.ReviewStatus.REJECTED)) {
            throw new SecurityException("Committed signed review is unavailable");
        }
        String proofId = NAMESPACE + ":" + proposal.reviewOperationId();
        ModelPromotionWebAuthnReviewEvidence receipt = mongo.findById(proofId,
                ModelPromotionWebAuthnReviewEvidence.class);
        GovernanceBoundWebAuthnProof proof = mongo.findById(proofId, GovernanceBoundWebAuthnProof.class);
        ModelPromotionDecisionDocument decision = mongo.findById(ModelPromotionPersistenceKey.operation(
                proposal.aggregateKey(), proposal.reviewOperationId()), ModelPromotionDecisionDocument.class);
        ModelPromotionDecisionDocument.EventType expectedEvent = approval.outcome()
                == ModelPromotionApproval.Outcome.APPROVED
                ? ModelPromotionDecisionDocument.EventType.LIFECYCLE_TRANSITION_ACCEPTED
                : ModelPromotionDecisionDocument.EventType.TRANSITION_PROPOSAL_REJECTED;
        if (receipt == null || proof == null || !proposalId.equals(receipt.proposalId())
                || decision == null || decision.eventType() != expectedEvent
                || !proposal.aggregateKey().equals(decision.aggregateKey())
                || !proposal.reviewOperationId().equals(decision.operationId())
                || !proposal.transition().artifactIdentity().equals(decision.artifactIdentity())
                || !proposal.transition().governanceScope().equals(decision.governanceScope())
                || proposal.transition().intendedUse() != decision.intendedUse()
                || !approval.equals(decision.approval())
                || !approval.approvedBy().equals(decision.actorId())
                || !Objects.equals(proposal.policySnapshot(), decision.policySnapshot())
                || !Objects.equals(proposal.assessmentSnapshot(), decision.assessmentSnapshot())
                || !proposal.proposal().equals(decision.proposal())
                || !proposal.reviewOperationId().equals(receipt.operationId())
                || !approval.approvalId().equals(receipt.approvalId())
                || approval.outcome() != receipt.outcome()
                || !approval.rationale().sha256().equals(receipt.rationaleSha256())
                || (proposal.reviewerIdentity() == null
                        ? !approval.approvedBy().equals(receipt.subject())
                        : !proposal.reviewerIdentity().actorId().equals(approval.approvedBy())
                                || !proposal.reviewerIdentity().actorId().equals(receipt.actorId())
                                || !proposal.reviewerIdentity().issuer().equals(receipt.issuer())
                                || !proposal.reviewerIdentity().subject().equals(receipt.subject()))
                || (proposal.reviewerIdentity() == null ? decision.actorIdentity() != null
                        : !proposal.reviewerIdentity().equals(decision.actorIdentity()))
                || (decision.actorIdentity() != null
                        && !decision.actorIdentity().actorId().equals(decision.actorId()))
                || !proof.id().equals(receipt.challengeId())
                || !proof.actorId().equals(receipt.actorId())
                || !proof.issuer().equals(receipt.issuer())
                || !proof.subject().equals(receipt.subject())
                || !proof.credentialId().equals(receipt.credentialId())
                || !proof.contractSha256().equals(receipt.contractSha256())
                || !proof.assertionSha256().equals(receipt.assertionSha256())
                || proof.signatureCount() != receipt.signatureCount()
                || !proof.acceptedAt().equals(receipt.acceptedAt())) {
            throw new SecurityException("Committed review and cryptographic proof differ");
        }
        ReviewIntent intent = new ReviewIntent(approval.approvalId(), approval.outcome(),
                proposal.reviewOperationId(), approval.rationale().content(), approval.rationale().sha256());
        long preReviewRevision = decision.resultingRevision()
                - (approval.outcome() == ModelPromotionApproval.Outcome.APPROVED ? 1 : 0);
        if (preReviewRevision < 0 || approval.outcome() == ModelPromotionApproval.Outcome.APPROVED
                && (preReviewRevision != proposal.transition().expectedRevision()
                    || decision.sourceState() != proposal.transition().sourceState())
                || decision.sourceState() == null) {
            throw new SecurityException("Committed review lifecycle revision is inconsistent");
        }
        historicalVerifier.verifyHistorical(proof, contract(proposal, intent,
                preReviewRevision, decision.sourceState()));
    }

    private ModelPromotionProposalDocument checkedProposal(PolicyGovernanceActor actor, String proposalId,
            ReviewIntent intent) {
        if (actor == null || intent == null || intent.outcome() == null) {
            throw new AccessDeniedException("Institutional OIDC reviewer required");
        }
        var proposal = store.findProposal(proposalId).orElseThrow(() ->
                new ModelPromotionPersistenceConflictException("Model promotion proposal unavailable"));
        String mandate = intent.outcome() == ModelPromotionApproval.Outcome.APPROVED
                ? AnalystAuthority.MODEL_PROMOTION_APPROVE : AnalystAuthority.MODEL_PROMOTION_REJECT;
        if (!actor.id().equals(authorization.requireActor(mandate,
                proposal.transition().governanceScope()))) {
            throw new AccessDeniedException("OIDC identity and promotion reviewer differ");
        }
        if (proposal.proposerIdentity() == null) {
            throw new ModelPromotionPersistenceConflictException(
                    "Legacy pending proposal has no issuer binding; resubmit with new proposal and operation IDs");
        }
        if (!proposal.proposal().proposedBy().equals(proposal.proposerIdentity().actorId())) {
            throw new SecurityException("Proposal actor identity is inconsistent");
        }
        if (proposal.proposerIdentity().actorId().equals(actor.id())) {
            throw new AccessDeniedException("Independent reviewer required");
        }
        new ModelPromotionDecisionRationale(ModelPromotionDecisionRationale.approvalReference(
                proposalId, intent.approvalId()), intent.rationale(), intent.rationaleSha256());
        return proposal;
    }

    private Map<String, Object> contract(ModelPromotionProposalDocument proposal, ReviewIntent intent) {
        var transition = proposal.transition();
        var identity = transition.artifactIdentity();
        var aggregate = store.currentState(identity, transition.governanceScope(), transition.intendedUse())
                .orElseThrow(() -> new ModelPromotionPersistenceConflictException(
                        "Current model promotion lifecycle unavailable"));
        return contract(proposal, intent, aggregate.revision(), aggregate.lifecycleState());
    }

    private Map<String, Object> contract(ModelPromotionProposalDocument proposal, ReviewIntent intent,
            long currentRevision, ModelPromotionLifecycleState currentState) {
        var transition = proposal.transition();
        var identity = transition.artifactIdentity();
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("domain", DOMAIN);
        contract.put("proposalId", proposal.proposalId());
        contract.put("proposalSha256", proposal.commandSha256());
        contract.put("proposalIntentSha256", proposal.requestIntentSha256());
        contract.put("operationId", intent.operationId());
        contract.put("approvalId", intent.approvalId());
        contract.put("decision", intent.outcome().name());
        contract.put("rationaleSha256", intent.rationaleSha256());
        contract.put("modelName", identity.modelName());
        contract.put("modelVersion", identity.modelVersion());
        contract.put("featureContractVersion", identity.featureContractVersion());
        contract.put("modelArtifactSha256", identity.modelArtifactSha256());
        contract.put("institutionalScope", transition.governanceScope().institutionalScope());
        contract.put("environment", transition.governanceScope().environment().name());
        contract.put("intendedUse", transition.intendedUse().name());
        contract.put("sourceState", transition.sourceState().name());
        contract.put("requestedTargetState", transition.requestedTargetState().name());
        contract.put("expectedRevision", transition.expectedRevision());
        contract.put("currentRevision", currentRevision);
        contract.put("currentState", currentState.name());
        contract.put("policySha256", proposal.policySnapshot() == null ? null
                : proposal.policySnapshot().canonicalSha256());
        contract.put("assessmentSha256", proposal.assessmentSnapshot() == null ? null
                : proposal.assessmentSnapshot().canonicalSha256());
        return contract;
    }

    public record ReviewIntent(String approvalId, ModelPromotionApproval.Outcome outcome,
            String operationId, String rationale, String rationaleSha256) { }
    public record SignedReview(ReviewIntent intent, String challengeId, String responseJson) { }
}
