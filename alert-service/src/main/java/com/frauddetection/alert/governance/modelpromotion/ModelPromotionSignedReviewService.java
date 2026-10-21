package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnService;
import com.frauddetection.alert.security.authorization.AnalystAuthority;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

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

    public ModelPromotionSignedReviewService(MongoModelPromotionStore store,
            ModelPromotionAuthorization authorization, ModelPromotionWorkflowService workflow,
            PolicyWebAuthnService webAuthn, MongoTemplate mongo) {
        this.store = store;
        this.authorization = authorization;
        this.workflow = workflow;
        this.webAuthn = webAuthn;
        this.mongo = mongo;
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

    private ModelPromotionProposalDocument checkedProposal(PolicyGovernanceActor actor, String proposalId,
            ReviewIntent intent) {
        if (actor == null || intent == null || intent.outcome() == null) {
            throw new AccessDeniedException("Institutional OIDC reviewer required");
        }
        var proposal = store.findProposal(proposalId).orElseThrow(() ->
                new ModelPromotionPersistenceConflictException("Model promotion proposal unavailable"));
        String mandate = intent.outcome() == ModelPromotionApproval.Outcome.APPROVED
                ? AnalystAuthority.MODEL_PROMOTION_APPROVE : AnalystAuthority.MODEL_PROMOTION_REJECT;
        if (!actor.subject().equals(authorization.requireActor(mandate,
                proposal.transition().governanceScope()))) {
            throw new AccessDeniedException("OIDC identity and promotion reviewer differ");
        }
        if (proposal.proposal().proposedBy().equals(actor.subject())) {
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
        contract.put("currentRevision", aggregate.revision());
        contract.put("currentState", aggregate.lifecycleState().name());
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
