package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;
import com.frauddetection.alert.security.authorization.AnalystAuthority;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class ModelPromotionWorkflowService {

    private final MongoModelPromotionStore store;
    private final ModelPromotionEvidenceResolver evidenceResolver;
    private final ModelPromotionAssessmentService assessmentService;
    private final ModelPromotionPolicyResolver policyResolver;
    private final ModelPromotionAuthorization authorization;
    private final Clock clock;

    @Autowired
    public ModelPromotionWorkflowService(
            MongoModelPromotionStore store,
            ModelPromotionEvidenceResolver evidenceResolver,
            ModelPromotionAssessmentService assessmentService,
            ModelPromotionPolicyResolver policyResolver,
            ModelPromotionAuthorization authorization
    ) {
        this(
                store,
                evidenceResolver,
                assessmentService,
                policyResolver,
                authorization,
                Clock.systemUTC()
        );
    }

    ModelPromotionWorkflowService(
            MongoModelPromotionStore store,
            ModelPromotionEvidenceResolver evidenceResolver,
            ModelPromotionAssessmentService assessmentService,
            ModelPromotionPolicyResolver policyResolver,
            ModelPromotionAuthorization authorization,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store);
        this.evidenceResolver = Objects.requireNonNull(evidenceResolver);
        this.assessmentService = Objects.requireNonNull(assessmentService);
        this.policyResolver = Objects.requireNonNull(policyResolver);
        this.authorization = Objects.requireNonNull(authorization);
        this.clock = Objects.requireNonNull(clock);
    }

    public ModelPromotionMutationResult registerCandidate(CandidateRegistrationRequest request) {
        Objects.requireNonNull(request, "request is required");
        Instant requestedAt = clock.instant();
        ModelPromotionAuthorization.AuthorizedActor actor = authorization.authorize(
                AnalystAuthority.MODEL_PROMOTION_REGISTER,
                request.governanceScope(),
                request.operationId(),
                requestedAt
        );
        return store.registerCandidate(new ModelPromotionRegistrationCommand(
                request.artifactIdentity(),
                request.governanceScope(),
                request.intendedUse(),
                actor.operationContext()
        ));
    }

    public ModelPromotionAssessment requestAssessment(AssessmentRequest request) {
        Objects.requireNonNull(request, "request is required");
        authorization.requireAuthority(AnalystAuthority.MODEL_PROMOTION_READ, request.governanceScope());
        return assessExact(
                request.artifactIdentity(),
                request.governanceScope(),
                request.intendedUse(),
                request.evaluationWindow(),
                clock.instant()
        );
    }

    public ModelPromotionProposalResult proposeTransition(TransitionProposalRequest request) {
        Objects.requireNonNull(request, "request is required");
        String requiredAuthority = request.requestedTargetState() == ModelPromotionLifecycleState.RETIRED
                ? AnalystAuthority.MODEL_PROMOTION_RETIRE
                : AnalystAuthority.MODEL_PROMOTION_PROPOSE;
        Instant requestedAt = clock.instant();
        ModelPromotionAuthorization.AuthorizedActor actor = authorization.authorize(
                requiredAuthority,
                request.governanceScope(),
                request.operationId(),
                requestedAt
        );
        ModelPromotionTransitionProposal transition = new ModelPromotionTransitionProposal(
                request.artifactIdentity(),
                request.governanceScope(),
                request.intendedUse(),
                request.sourceState(),
                request.expectedRevision(),
                request.requestedTargetState(),
                request.operationId()
        );
        ModelPromotionDecisionRationale rationale = new ModelPromotionDecisionRationale(
                ModelPromotionDecisionRationale.proposalReference(request.proposalId()),
                request.rationale(),
                request.rationaleSha256()
        );
        ModelPromotionProposalRequestIntent requestIntent = new ModelPromotionProposalRequestIntent(
                transition, request.evaluationWindow(), request.proposalId(), actor.actorId(), rationale
        );
        Optional<ModelPromotionProposalResult> committed = store.recoverSubmittedProposal(
                requestIntent, actor.operationContext()
        );
        if (committed.isPresent()) {
            return committed.get();
        }
        boolean qualityGated = requiresAssessment(request.requestedTargetState());
        ModelPromotionPolicy policy = qualityGated
                ? currentPolicy(request.governanceScope(), request.intendedUse(), requestedAt)
                : null;
        ModelPromotionAssessment assessment = qualityGated
                ? assessExact(
                request.artifactIdentity(),
                request.governanceScope(),
                request.intendedUse(),
                request.evaluationWindow(),
                requestedAt
        )
                : null;
        if (assessment != null && !matchesPolicy(assessment, Objects.requireNonNull(policy))) {
            throw new ModelPromotionWorkflowBlockedException(
                    ModelPromotionWorkflowBlockedException.Reason.POLICY_CHANGED,
                    "The promotion policy changed while the assessment was being created"
            );
        }
        ModelPromotionProposal proposal = new ModelPromotionProposal(
                request.proposalId(),
                request.requestedTargetState(),
                actor.actorId(),
                requestedAt,
                rationale
        );
        authorization.revalidate(requiredAuthority, request.governanceScope(), actor.operationContext());
        return store.submitProposal(new ModelPromotionProposalSubmission(
                transition,
                actor.operationContext(),
                policy,
                assessment,
                proposal
        ), requestIntent);
    }

    public ModelPromotionProposalResult reviewProposal(ReviewRequest request) {
        Objects.requireNonNull(request, "request is required");
        ModelPromotionProposalDocument proposal = store.findProposal(request.proposalId())
                .orElseThrow(() -> new ModelPromotionWorkflowBlockedException(
                        ModelPromotionWorkflowBlockedException.Reason.PROPOSAL_NOT_FOUND,
                        "The model promotion proposal is unavailable"
                ));
        String requiredAuthority = request.outcome() == ModelPromotionApproval.Outcome.APPROVED
                ? AnalystAuthority.MODEL_PROMOTION_APPROVE
                : AnalystAuthority.MODEL_PROMOTION_REJECT;
        Instant requestedAt = clock.instant();
        ModelPromotionAuthorization.AuthorizedActor actor = authorization.authorize(
                requiredAuthority,
                proposal.transition().governanceScope(),
                request.operationId(),
                requestedAt
        );
        if (proposal.proposal().proposedBy().equals(actor.actorId())) {
            throw new ModelPromotionPersistenceConflictException(
                    "A model promotion proposer cannot review their own proposal"
            );
        }
        ModelPromotionProposalReview review = new ModelPromotionProposalReview(
                proposal.proposalId(),
                new ModelPromotionApproval(
                        request.approvalId(),
                        proposal.proposalId(),
                        request.outcome(),
                        actor.actorId(),
                        requestedAt,
                        new ModelPromotionDecisionRationale(
                                ModelPromotionDecisionRationale.approvalReference(
                                        proposal.proposalId(), request.approvalId()
                                ),
                                request.rationale(),
                                request.rationaleSha256()
                        )
                ),
                actor.operationContext()
        );
        return store.reviewProposal(review, () -> revalidateReviewPreconditions(
                proposal,
                requiredAuthority,
                actor.operationContext(),
                request.outcome()
        ));
    }

    public Optional<ModelPromotionAggregate> currentState(StateRequest request) {
        Objects.requireNonNull(request, "request is required");
        authorization.requireAuthority(AnalystAuthority.MODEL_PROMOTION_READ, request.governanceScope());
        return store.currentState(request.artifactIdentity(), request.governanceScope(), request.intendedUse());
    }

    public List<ModelPromotionDecisionDocument> decisionHistory(StateRequest request, int limit) {
        Objects.requireNonNull(request, "request is required");
        authorization.requireAuthority(AnalystAuthority.MODEL_PROMOTION_READ, request.governanceScope());
        return store.history(request.artifactIdentity(), request.governanceScope(), request.intendedUse(), limit);
    }

    public boolean canReadRationale(StateRequest request) {
        Objects.requireNonNull(request, "request is required");
        authorization.requireAuthority(AnalystAuthority.MODEL_PROMOTION_READ, request.governanceScope());
        return authorization.canReadRationale(request.governanceScope());
    }

    public List<ModelPromotionProposalDocument> proposalHistory(StateRequest request, int limit) {
        Objects.requireNonNull(request, "request is required");
        authorization.requireAuthority(AnalystAuthority.MODEL_PROMOTION_READ, request.governanceScope());
        return store.proposals(request.artifactIdentity(), request.governanceScope(), request.intendedUse(), limit);
    }

    private void revalidateReviewPreconditions(
            ModelPromotionProposalDocument proposal,
            String requiredAuthority,
            ModelPromotionOperationContext operationContext,
            ModelPromotionApproval.Outcome outcome
    ) {
        authorization.revalidate(
                requiredAuthority,
                proposal.transition().governanceScope(),
                operationContext
        );
        if (outcome != ModelPromotionApproval.Outcome.APPROVED) {
            return;
        }
        if (!requiresAssessment(proposal.transition().requestedTargetState())) {
            return;
        }
        ModelPromotionPolicy currentPolicy = currentPolicy(
                proposal.transition().governanceScope(),
                proposal.transition().intendedUse(),
                clock.instant()
        );
        if (proposal.policySnapshot() == null
                || !currentPolicy.canonicalSha256().equals(proposal.policySnapshot().canonicalSha256())) {
            throw new ModelPromotionWorkflowBlockedException(
                    ModelPromotionWorkflowBlockedException.Reason.POLICY_CHANGED,
                    "The promotion policy changed after the proposal was submitted"
            );
        }
        ModelPromotionAssessment assessment = Objects.requireNonNull(
                proposal.assessmentSnapshot(), "assessment snapshot is required for this proposal"
        );
        ModelPromotionEvidenceResolution currentEvidence = evidenceResolver.resolve(
                proposal.transition().artifactIdentity(),
                proposal.transition().governanceScope(),
                proposal.transition().intendedUse(),
                assessment.content().evidence().evaluationWindow()
        );
        if (currentEvidence.status() != ModelPromotionEvidenceResolution.Status.AVAILABLE
                || !currentEvidence.evidenceBundle().canonicalSha256().equals(
                assessment.content().evidence().evidenceBundleSha256()
        )) {
            throw new ModelPromotionWorkflowBlockedException(
                    ModelPromotionWorkflowBlockedException.Reason.EVIDENCE_CHANGED,
                    "The exact assessment evidence changed after the proposal was submitted"
            );
        }
    }

    private ModelPromotionAssessment assessExact(
            ExactModelArtifactIdentity artifactIdentity,
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse,
            ModelPromotionEvaluationWindow evaluationWindow,
            Instant assessmentTime
    ) {
        ModelPromotionEvidenceResolution evidenceResolution = evidenceResolver.resolve(
                artifactIdentity,
                governanceScope,
                intendedUse,
                evaluationWindow
        );
        if (evidenceResolution.status() != ModelPromotionEvidenceResolution.Status.AVAILABLE) {
            throw new ModelPromotionWorkflowBlockedException(
                    ModelPromotionWorkflowBlockedException.Reason.EVIDENCE_UNAVAILABLE,
                    "Exact model-promotion evidence is unavailable"
            );
        }
        return assessmentService.assess(evidenceResolution.evidenceBundle(), assessmentTime);
    }

    private ModelPromotionPolicy currentPolicy(
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse,
            Instant requestedAt
    ) {
        ModelPromotionPolicyResolution resolution = policyResolver.resolve(
                governanceScope,
                intendedUse,
                requestedAt
        );
        if (resolution.status() != ModelPromotionPolicyResolution.Status.AVAILABLE) {
            throw new ModelPromotionWorkflowBlockedException(
                    ModelPromotionWorkflowBlockedException.Reason.POLICY_UNAVAILABLE,
                    "An approved model-promotion policy is unavailable"
            );
        }
        return resolution.policy();
    }

    private boolean matchesPolicy(ModelPromotionAssessment assessment, ModelPromotionPolicy policy) {
        ModelPromotionAssessment.PolicyReference assessmentPolicy = assessment.content().policy();
        return assessmentPolicy.resolutionStatus() == ModelPromotionPolicyResolution.Status.AVAILABLE
                && assessmentPolicy.policyId().equals(policy.content().policyId())
                && assessmentPolicy.revision().equals(policy.content().revision())
                && assessmentPolicy.canonicalSha256().equals(policy.canonicalSha256());
    }

    private boolean requiresAssessment(ModelPromotionLifecycleState targetState) {
        return targetState == ModelPromotionLifecycleState.SHADOW_APPROVED
                || targetState == ModelPromotionLifecycleState.PROMOTION_RECOMMENDED
                || targetState == ModelPromotionLifecycleState.CHAMPION;
    }

    public record CandidateRegistrationRequest(
            ExactModelArtifactIdentity artifactIdentity,
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse,
            String operationId
    ) {
        public CandidateRegistrationRequest {
            Objects.requireNonNull(artifactIdentity, "artifactIdentity is required");
            Objects.requireNonNull(governanceScope, "governanceScope is required");
            Objects.requireNonNull(intendedUse, "intendedUse is required");
        }
    }

    public record StateRequest(
            ExactModelArtifactIdentity artifactIdentity,
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse
    ) {
        public StateRequest {
            Objects.requireNonNull(artifactIdentity, "artifactIdentity is required");
            Objects.requireNonNull(governanceScope, "governanceScope is required");
            Objects.requireNonNull(intendedUse, "intendedUse is required");
        }
    }

    public record AssessmentRequest(
            ExactModelArtifactIdentity artifactIdentity,
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse,
            ModelPromotionEvaluationWindow evaluationWindow
    ) {
        public AssessmentRequest {
            Objects.requireNonNull(artifactIdentity, "artifactIdentity is required");
            Objects.requireNonNull(governanceScope, "governanceScope is required");
            Objects.requireNonNull(intendedUse, "intendedUse is required");
            Objects.requireNonNull(evaluationWindow, "evaluationWindow is required");
        }
    }

    public record TransitionProposalRequest(
            ExactModelArtifactIdentity artifactIdentity,
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse,
            ModelPromotionLifecycleState sourceState,
            long expectedRevision,
            ModelPromotionLifecycleState requestedTargetState,
            ModelPromotionEvaluationWindow evaluationWindow,
            String operationId,
            String proposalId,
            String rationale,
            String rationaleSha256
    ) {
        public TransitionProposalRequest {
            Objects.requireNonNull(artifactIdentity, "artifactIdentity is required");
            Objects.requireNonNull(governanceScope, "governanceScope is required");
            Objects.requireNonNull(intendedUse, "intendedUse is required");
            Objects.requireNonNull(sourceState, "sourceState is required");
            Objects.requireNonNull(requestedTargetState, "requestedTargetState is required");
            Objects.requireNonNull(evaluationWindow, "evaluationWindow is required");
        }
    }

    public record ReviewRequest(
            String proposalId,
            String approvalId,
            ModelPromotionApproval.Outcome outcome,
            String operationId,
            String rationale,
            String rationaleSha256
    ) {
        public ReviewRequest {
            if (proposalId == null || proposalId.isBlank()) {
                throw new IllegalArgumentException("proposalId is required");
            }
            if (approvalId == null || approvalId.isBlank()) {
                throw new IllegalArgumentException("approvalId is required");
            }
            Objects.requireNonNull(outcome, "outcome is required");
        }
    }
}
