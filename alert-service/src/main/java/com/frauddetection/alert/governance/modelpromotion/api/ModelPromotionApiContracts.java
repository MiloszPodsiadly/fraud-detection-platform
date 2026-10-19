package com.frauddetection.alert.governance.modelpromotion.api;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;
import com.frauddetection.alert.governance.modelpromotion.ModelGovernanceEnvironment;
import com.frauddetection.alert.governance.modelpromotion.ModelGovernanceScope;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionApproval;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionEvaluationWindow;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionIntendedUse;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionLifecycleState;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionWorkflowService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ModelPromotionApiContracts {

    private static final String IDENTIFIER = "^[A-Za-z0-9._-]{1,96}$";
    private static final String SHA256 = "^[0-9a-f]{64}$";
    private static final String OPERATION_ID = "^[A-Za-z0-9._:@-]{1,128}$";

    private ModelPromotionApiContracts() {
    }

    public record Selection(
            @NotBlank @Pattern(regexp = IDENTIFIER) String modelName,
            @NotBlank @Pattern(regexp = IDENTIFIER) String modelVersion,
            @NotBlank @Pattern(regexp = IDENTIFIER) String featureContractVersion,
            @NotBlank @Pattern(regexp = SHA256) String modelArtifactSha256,
            @NotNull ModelGovernanceEnvironment environment,
            @NotNull ModelPromotionIntendedUse intendedUse
    ) {
        public ExactModelArtifactIdentity artifactIdentity() {
            return new ExactModelArtifactIdentity(modelName, modelVersion, featureContractVersion, modelArtifactSha256);
        }

        public ModelGovernanceScope governanceScope() {
            return new ModelGovernanceScope(ModelGovernanceScope.CURRENT_INSTITUTION, environment);
        }

        public ModelPromotionWorkflowService.StateRequest stateRequest() {
            return new ModelPromotionWorkflowService.StateRequest(artifactIdentity(), governanceScope(), intendedUse);
        }
    }

    public record AssessmentSelection(@Valid @NotNull Selection selection, @NotBlank String fromInclusive, @NotBlank String toInclusive) {
        public ModelPromotionEvaluationWindow evaluationWindow() {
            return new ModelPromotionEvaluationWindow(fromInclusive, toInclusive);
        }
    }

    public record CandidateRequest(@Valid @NotNull Selection selection, @NotBlank @Pattern(regexp = OPERATION_ID) String operationId) {
    }

    public record ProposalRequest(
            @Valid @NotNull Selection selection,
            @NotNull ModelPromotionLifecycleState sourceState,
            @Min(0) long expectedRevision,
            @NotNull ModelPromotionLifecycleState requestedTargetState,
            @NotBlank String fromInclusive,
            @NotBlank String toInclusive,
            @NotBlank @Pattern(regexp = OPERATION_ID) String operationId,
            @NotBlank @Pattern(regexp = OPERATION_ID) String proposalId,
            @NotBlank @Size(max = 4096) String rationale,
            @NotBlank @Pattern(regexp = SHA256) String rationaleSha256
    ) {
        public ModelPromotionEvaluationWindow evaluationWindow() {
            return new ModelPromotionEvaluationWindow(fromInclusive, toInclusive);
        }
    }

    public record RetirementProposalRequest(
            @Valid @NotNull Selection selection,
            @NotNull ModelPromotionLifecycleState sourceState,
            @Min(0) long expectedRevision,
            @NotBlank String fromInclusive,
            @NotBlank String toInclusive,
            @NotBlank @Pattern(regexp = OPERATION_ID) String operationId,
            @NotBlank @Pattern(regexp = OPERATION_ID) String proposalId,
            @NotBlank @Size(max = 4096) String rationale,
            @NotBlank @Pattern(regexp = SHA256) String rationaleSha256
    ) {
        public ModelPromotionEvaluationWindow evaluationWindow() {
            return new ModelPromotionEvaluationWindow(fromInclusive, toInclusive);
        }
    }

    public record ReviewRequest(
            @NotBlank @Pattern(regexp = OPERATION_ID) String approvalId,
            @NotNull ModelPromotionApproval.Outcome outcome,
            @NotBlank @Pattern(regexp = OPERATION_ID) String operationId,
            @NotBlank @Size(max = 4096) String rationale,
            @NotBlank @Pattern(regexp = SHA256) String rationaleSha256
    ) {
    }

    public record StateResponse(
            IdentityResponse artifactIdentity,
            ScopeResponse governanceScope,
            ModelPromotionIntendedUse intendedUse,
            ModelPromotionLifecycleState lifecycleState,
            long currentRevision,
            String acceptedDecisionId,
            List<ModelPromotionLifecycleState> structurallyAllowedTargetStates,
            boolean runtimeActivationAuthorized,
            String deploymentStatus,
            List<DecisionResponse> decisionHistory,
            List<ProposalResponse> proposals
    ) {
    }

    public record IdentityResponse(
            String modelName,
            String modelVersion,
            String featureContractVersion,
            String modelArtifactSha256
    ) {
    }

    public record ScopeResponse(String institutionalScope, ModelGovernanceEnvironment environment) {
    }

    public record DecisionResponse(
            String eventType,
            ModelPromotionLifecycleState sourceState,
            ModelPromotionLifecycleState resultingState,
            long resultingRevision,
            String decisionId,
            String policySha256,
            String assessmentSha256,
            String proposalId,
            String reviewOutcome,
            DecisionRationaleResponse proposalRationale,
            DecisionRationaleResponse approvalRationale,
            Instant committedAt
    ) {
    }

    public record ProposalResponse(
            String proposalId,
            ModelPromotionLifecycleState requestedTargetState,
            String reviewStatus,
            String reviewOutcome,
            String policySha256,
            String assessmentSha256,
            Instant submittedAt,
            Instant reviewedAt,
            DecisionRationaleResponse proposalRationale,
            DecisionRationaleResponse approvalRationale,
            boolean idempotentReplay
    ) {
    }

    public record MutationResponse(
            IdentityResponse artifactIdentity,
            ScopeResponse governanceScope,
            ModelPromotionIntendedUse intendedUse,
            ModelPromotionLifecycleState lifecycleState,
            long currentRevision,
            String decisionId,
            String operationId,
            Instant committedAt,
            boolean idempotentReplay,
            String deploymentStatus
    ) {
    }

    public record DecisionRationaleResponse(String referenceId, String sha256, String content) {
    }

    public record AssessmentResponse(
            String assessmentSha256,
            String assessmentCreatedAt,
            IdentityResponse artifactIdentity,
            ScopeResponse governanceScope,
            ModelPromotionIntendedUse intendedUse,
            PolicyResponse appliedPolicy,
            EvidenceResponse sourceEvidence,
            PopulationResponse populationAndCoverage,
            List<CriterionResponse> criteria,
            String assessmentStatus,
            boolean diagnosticEvidenceAvailable,
            boolean humanReviewable,
            boolean formalRecommendationEligible,
            boolean governanceApproved,
            boolean deploymentAuthorized,
            String deploymentStatus,
            List<String> blockers,
            List<String> limitations
    ) {
    }

    public record PolicyResponse(
            String resolutionStatus,
            String resolutionReason,
            String policyId,
            Long revision,
            String canonicalSha256,
            String authority
    ) {
    }

    public record EvidenceResponse(
            String evidenceBundleSha256,
            String modelCardManifestSha256,
            String shadowPerformanceManifestSha256,
            String fromInclusive,
            String toInclusive
    ) {
    }

    public record PopulationResponse(
            int recordsConsidered,
            int recordsEvaluated,
            int positiveClassCount,
            int negativeClassCount,
            boolean sourceDatasetTruncated,
            String returnedCohortStatus,
            String sourcePopulationStatus,
            int criticalLineageGapCount
    ) {
    }

    public record CriterionResponse(
            String criterion,
            String outcome,
            String reason,
            Map<String, String> observedValues,
            String evidenceSource,
            String metricSemantics,
            String populationDefinition,
            String denominatorDefinition,
            String requiredVerificationLevel,
            Long maximumEvidenceAgeSeconds
    ) {
    }
}
