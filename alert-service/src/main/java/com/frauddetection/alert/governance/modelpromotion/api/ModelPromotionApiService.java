package com.frauddetection.alert.governance.modelpromotion.api;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;
import com.frauddetection.alert.governance.modelpromotion.ModelGovernanceScope;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionAggregate;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionAssessment;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionDecisionDocument;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionDecisionRationale;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionLifecycleState;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionMutationResult;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionPolicyStoreUnavailableException;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionProposalDocument;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionProposalResult;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionStateMachine;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionWorkflowService;
import com.mongodb.MongoException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Arrays;
import java.util.function.Supplier;

import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.AssessmentResponse;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.AssessmentSelection;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.CandidateRequest;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.CriterionResponse;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.DecisionResponse;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.DecisionRationaleResponse;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.EvidenceResponse;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.IdentityResponse;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.MutationResponse;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.PolicyResponse;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.PopulationResponse;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.ProposalRequest;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.ProposalResponse;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.RetirementProposalRequest;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.ReviewRequest;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.ScopeResponse;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.Selection;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.StateResponse;

@Service
public class ModelPromotionApiService {

    private static final String DEPLOYMENT_STATUS = "NOT_AUTHORIZED_BY_MODEL_PROMOTION_GOVERNANCE";

    private final ModelPromotionWorkflowService workflowService;
    private final ModelPromotionStateMachine stateMachine = new ModelPromotionStateMachine();

    public ModelPromotionApiService(ModelPromotionWorkflowService workflowService) {
        this.workflowService = Objects.requireNonNull(workflowService);
    }

    public StateResponse state(Selection selection, int historyLimit) {
        return available(() -> {
            ModelPromotionAggregate aggregate = workflowService.currentState(selection.stateRequest())
                    .orElseThrow(() -> new ModelPromotionApiNotFoundException("Model promotion state was not found."));
            List<ModelPromotionDecisionDocument> decisions = workflowService.decisionHistory(
                    selection.stateRequest(), historyLimit
            );
            List<ModelPromotionProposalDocument> proposals = workflowService.proposalHistory(
                    selection.stateRequest(), historyLimit
            );
            boolean includeRationale = workflowService.canReadRationale(selection.stateRequest());
            return new StateResponse(
                    identity(aggregate.artifactIdentity()),
                    scope(aggregate.governanceScope()),
                    aggregate.intendedUse(),
                    aggregate.lifecycleState(),
                    aggregate.revision(),
                    aggregate.acceptedDecisionId(),
                    Arrays.stream(ModelPromotionLifecycleState.values())
                            .filter(target -> stateMachine.canTransition(aggregate.lifecycleState(), target))
                            .toList(),
                    aggregate.authorizesRuntimeActivation(),
                    DEPLOYMENT_STATUS,
                    decisions.stream().map(decision -> decision(decision, includeRationale)).toList(),
                    proposals.stream().map(proposal -> proposal(proposal, false, includeRationale)).toList()
            );
        });
    }

    public AssessmentResponse assessment(AssessmentSelection selection) {
        return available(() -> assessment(workflowService.requestAssessment(
                new ModelPromotionWorkflowService.AssessmentRequest(
                        selection.selection().artifactIdentity(),
                        selection.selection().governanceScope(),
                        selection.selection().intendedUse(),
                        selection.evaluationWindow()
                )
        )));
    }

    public MutationResponse register(CandidateRequest request) {
        return available(() -> mutation(workflowService.registerCandidate(
                new ModelPromotionWorkflowService.CandidateRegistrationRequest(
                        request.selection().artifactIdentity(),
                        request.selection().governanceScope(),
                        request.selection().intendedUse(),
                        request.operationId()
                )
        )));
    }

    public ProposalResponse propose(ProposalRequest request) {
        if (request.requestedTargetState() == ModelPromotionLifecycleState.RETIRED) {
            throw new IllegalArgumentException("Use the retirement proposal command for RETIRED state.");
        }
        return available(() -> proposal(workflowService.proposeTransition(
                new ModelPromotionWorkflowService.TransitionProposalRequest(
                        request.selection().artifactIdentity(),
                        request.selection().governanceScope(),
                        request.selection().intendedUse(),
                        request.sourceState(),
                        request.expectedRevision(),
                        request.requestedTargetState(),
                        request.evaluationWindow(),
                        request.operationId(),
                        request.proposalId(),
                        request.rationale(),
                        request.rationaleSha256()
                )
        ), false));
    }

    public ProposalResponse retire(RetirementProposalRequest request) {
        return available(() -> proposal(workflowService.proposeTransition(
                new ModelPromotionWorkflowService.TransitionProposalRequest(
                        request.selection().artifactIdentity(),
                        request.selection().governanceScope(),
                        request.selection().intendedUse(),
                        request.sourceState(),
                        request.expectedRevision(),
                        ModelPromotionLifecycleState.RETIRED,
                        request.evaluationWindow(),
                        request.operationId(),
                        request.proposalId(),
                        request.rationale(),
                        request.rationaleSha256()
                )
        ), false));
    }

    public ProposalResponse review(String proposalId, ReviewRequest request) {
        return available(() -> proposal(workflowService.reviewProposal(
                new ModelPromotionWorkflowService.ReviewRequest(
                        proposalId,
                        request.approvalId(),
                        request.outcome(),
                        request.operationId(),
                        request.rationale(),
                        request.rationaleSha256()
                )
        ), false));
    }

    public ProposalResponse fromSignedReviewResult(ModelPromotionProposalResult result) {
        return proposal(result, false);
    }

    private AssessmentResponse assessment(ModelPromotionAssessment assessment) {
        ModelPromotionAssessment.Content content = assessment.content();
        ModelPromotionAssessment.PolicyReference policy = content.policy();
        ModelPromotionAssessment.EvidenceReference evidence = content.evidence();
        ModelPromotionAssessment.PopulationAndCoverage population = content.populationAndCoverage();
        return new AssessmentResponse(
                assessment.canonicalSha256(),
                content.assessmentCreatedAt(),
                identity(content.artifactIdentity()),
                scope(content.governanceScope()),
                content.intendedUse(),
                new PolicyResponse(
                        policy.resolutionStatus().name(),
                        policy.resolutionReason().name(),
                        policy.policyId(),
                        policy.revision(),
                        policy.canonicalSha256(),
                        policy.authority() == null ? null : policy.authority().name()
                ),
                new EvidenceResponse(
                        evidence.evidenceBundleSha256(),
                        evidence.modelCardManifestSha256(),
                        evidence.shadowPerformanceManifestSha256(),
                        evidence.evaluationWindow().fromInclusive(),
                        evidence.evaluationWindow().toInclusive()
                ),
                new PopulationResponse(
                        population.recordsConsidered(),
                        population.recordsEvaluated(),
                        population.positiveClassCount(),
                        population.negativeClassCount(),
                        population.sourceDatasetTruncated(),
                        population.returnedCohortStatus(),
                        population.sourcePopulationStatus(),
                        population.criticalLineageGapCount()
                ),
                content.criteria().stream().map(criterion -> new CriterionResponse(
                        criterion.criterion().name(),
                        criterion.outcome().name(),
                        criterion.reason(),
                        criterion.observedValues(),
                        criterion.evidenceSource(),
                        criterion.metricSemantics(),
                        criterion.populationDefinition(),
                        criterion.denominatorDefinition(),
                        criterion.requiredVerificationLevel(),
                        criterion.maximumEvidenceAgeSeconds()
                )).toList(),
                content.assessmentStatus().name(),
                content.diagnosticEvidenceAvailable(),
                content.humanReviewable(),
                content.formalRecommendationEligible(),
                content.governanceApproved(),
                content.deploymentAuthorized(),
                DEPLOYMENT_STATUS,
                content.blockers(),
                content.limitations()
        );
    }

    private MutationResponse mutation(ModelPromotionMutationResult result) {
        ModelPromotionAggregate aggregate = result.aggregate();
        return new MutationResponse(
                identity(aggregate.artifactIdentity()),
                scope(aggregate.governanceScope()),
                aggregate.intendedUse(),
                aggregate.lifecycleState(),
                aggregate.revision(),
                result.decisionId(),
                result.operationId(),
                result.committedAt(),
                result.idempotentReplay(),
                DEPLOYMENT_STATUS
        );
    }

    private DecisionResponse decision(ModelPromotionDecisionDocument decision, boolean includeRationale) {
        return new DecisionResponse(
                decision.eventType().name(),
                decision.sourceState(),
                decision.resultingState(),
                decision.resultingRevision(),
                decision.decisionId(),
                decision.policySnapshot() == null ? null : decision.policySnapshot().canonicalSha256(),
                decision.assessmentSnapshot() == null ? null : decision.assessmentSnapshot().canonicalSha256(),
                decision.proposal() == null ? null : decision.proposal().proposalId(),
                decision.approval() == null ? null : decision.approval().outcome().name(),
                decision.proposal() == null ? null : rationale(decision.proposal().rationale(), includeRationale),
                decision.approval() == null ? null : rationale(decision.approval().rationale(), includeRationale),
                decision.committedAt()
        );
    }

    private ProposalResponse proposal(ModelPromotionProposalResult proposal, boolean includeRationale) {
        return new ProposalResponse(
                proposal.proposalId(),
                proposal.proposal().requestedState(),
                proposal.reviewStatus().name(),
                proposal.approval() == null ? null : proposal.approval().outcome().name(),
                proposal.policySha256(),
                proposal.assessmentSha256(),
                proposal.submittedAt(),
                proposal.reviewedAt(),
                rationale(proposal.proposal().rationale(), includeRationale),
                proposal.approval() == null ? null : rationale(proposal.approval().rationale(), includeRationale),
                proposal.idempotentReplay()
        );
    }

    private ProposalResponse proposal(
            ModelPromotionProposalDocument proposal, boolean idempotentReplay, boolean includeRationale
    ) {
        return new ProposalResponse(
                proposal.proposalId(),
                proposal.proposal().requestedState(),
                proposal.reviewStatus().name(),
                proposal.approval() == null ? null : proposal.approval().outcome().name(),
                proposal.policySnapshot() == null ? null : proposal.policySnapshot().canonicalSha256(),
                proposal.assessmentSnapshot() == null ? null : proposal.assessmentSnapshot().canonicalSha256(),
                proposal.submittedAt(),
                proposal.reviewedAt(),
                rationale(proposal.proposal().rationale(), includeRationale),
                proposal.approval() == null ? null : rationale(proposal.approval().rationale(), includeRationale),
                idempotentReplay
        );
    }

    private DecisionRationaleResponse rationale(ModelPromotionDecisionRationale rationale, boolean includeRationale) {
        return new DecisionRationaleResponse(
                rationale.referenceId(), rationale.sha256(), includeRationale ? rationale.content() : null
        );
    }

    private IdentityResponse identity(ExactModelArtifactIdentity identity) {
        return new IdentityResponse(
                identity.modelName(),
                identity.modelVersion(),
                identity.featureContractVersion(),
                identity.modelArtifactSha256()
        );
    }

    private ScopeResponse scope(ModelGovernanceScope scope) {
        return new ScopeResponse(scope.institutionalScope(), scope.environment());
    }

    private <T> T available(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (ModelPromotionPolicyStoreUnavailableException | DataAccessException | MongoException exception) {
            throw new ModelPromotionApiUnavailableException(
                    "Model promotion state or mandatory governance evidence is unavailable.", exception
            );
        }
    }
}
