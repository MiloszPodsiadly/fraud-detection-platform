package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelPromotionStateMachineTest {

    private final ModelPromotionStateMachine stateMachine = new ModelPromotionStateMachine();

    @Test
    void candidateRegistrationDoesNotImplyApprovalOrRuntimeActivation() {
        ModelPromotionAggregate candidate = candidate(scope(ModelGovernanceEnvironment.PRODUCTION));

        assertThat(candidate.lifecycleState()).isEqualTo(ModelPromotionLifecycleState.CANDIDATE);
        assertThat(candidate.revision()).isZero();
        assertThat(candidate.acceptedDecisionId()).isNull();
        assertThat(candidate.authorizesRuntimeActivation()).isFalse();
    }

    @Test
    void followsTheExplicitForwardLifecycleWithoutRuntimeActivation() {
        ModelPromotionAggregate current = candidate(scope(ModelGovernanceEnvironment.PRODUCTION));
        current = transition(current, ModelPromotionLifecycleState.CHALLENGER, null, 1);
        current = transition(current, ModelPromotionLifecycleState.SHADOW_APPROVED, "assessment-2", 2);
        current = transition(current, ModelPromotionLifecycleState.PROMOTION_RECOMMENDED, "assessment-3", 3);
        current = transition(current, ModelPromotionLifecycleState.CHAMPION, "assessment-4", 4);

        assertThat(current.revision()).isEqualTo(4);
        assertThat(current.acceptedDecisionId()).isEqualTo("decision-4");
        assertThat(current.authorizesRuntimeActivation()).isFalse();

        ModelPromotionAggregate retired = transition(current, ModelPromotionLifecycleState.RETIRED, null, 5);
        assertThat(retired.lifecycleState()).isEqualTo(ModelPromotionLifecycleState.RETIRED);
        assertThat(stateMachine.isTerminal(retired.lifecycleState())).isTrue();
        assertThatThrownBy(() -> transition(
                retired, ModelPromotionLifecycleState.CANDIDATE, null, 6
        )).isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("Unsupported model promotion transition");
    }

    @Test
    void rejectsUnsupportedAndTerminalReactivationTransitions() {
        ModelPromotionAggregate candidate = candidate(scope(ModelGovernanceEnvironment.TEST));
        assertThatThrownBy(() -> transition(
                candidate, ModelPromotionLifecycleState.CHAMPION, "assessment", 1
        )).isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("Unsupported model promotion transition");

        ModelPromotionAggregate rejected = transition(candidate, ModelPromotionLifecycleState.REJECTED, null, 1);
        assertThat(stateMachine.isTerminal(ModelPromotionLifecycleState.REJECTED)).isTrue();
        assertThatThrownBy(() -> transition(
                rejected, ModelPromotionLifecycleState.CHALLENGER, null, 2
        )).isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("Unsupported model promotion transition");
    }

    @Test
    void rejectsStateRevisionScopeAndArtifactMismatch() {
        ModelPromotionAggregate candidate = candidate(scope(ModelGovernanceEnvironment.PRODUCTION));

        assertThatThrownBy(() -> stateMachine.transition(candidate, command(
                candidate, ModelPromotionLifecycleState.CHALLENGER, null, 1,
                candidate.artifactIdentity(), candidate.governanceScope(), 3,
                ModelPromotionLifecycleState.CANDIDATE
        ))).isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("revision");
        assertThatThrownBy(() -> stateMachine.transition(candidate, command(
                candidate, ModelPromotionLifecycleState.CHALLENGER, null, 1,
                candidate.artifactIdentity(), candidate.governanceScope(), 0,
                ModelPromotionLifecycleState.CHALLENGER
        ))).hasMessageContaining("source state");
        assertThatThrownBy(() -> stateMachine.transition(candidate, command(
                candidate, ModelPromotionLifecycleState.CHALLENGER, null, 1,
                candidate.artifactIdentity(), scope(ModelGovernanceEnvironment.TEST), 0,
                ModelPromotionLifecycleState.CANDIDATE
        ))).hasMessageContaining("governance scope");
        assertThatThrownBy(() -> stateMachine.transition(candidate, command(
                candidate, ModelPromotionLifecycleState.CHALLENGER, null, 1,
                identity("b".repeat(64)), candidate.governanceScope(), 0,
                ModelPromotionLifecycleState.CANDIDATE
        ))).hasMessageContaining("artifact identity");
    }

    @Test
    void requiresAssessmentReferenceForEvidenceBoundStates() {
        ModelPromotionAggregate challenger = transition(
                candidate(scope(ModelGovernanceEnvironment.PRODUCTION)),
                ModelPromotionLifecycleState.CHALLENGER,
                null,
                1
        );

        assertThatThrownBy(() -> transition(
                challenger, ModelPromotionLifecycleState.SHADOW_APPROVED, null, 2
        )).isInstanceOf(ModelPromotionPersistenceConflictException.class)
                .hasMessageContaining("assessment reference");
    }

    @Test
    void rejectsUnsupportedInstitutionalScope() {
        assertThatThrownBy(() -> new ModelGovernanceScope(
                "UNVERIFIED_OTHER_INSTITUTION", ModelGovernanceEnvironment.PRODUCTION
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fixed current institutional scope");
    }

    private ModelPromotionAggregate transition(
            ModelPromotionAggregate current,
            ModelPromotionLifecycleState target,
            String assessmentId,
            int sequence
    ) {
        return stateMachine.transition(current, command(
                current,
                target,
                assessmentId,
                sequence,
                current.artifactIdentity(),
                current.governanceScope(),
                current.revision(),
                current.lifecycleState()
        ));
    }

    private ModelPromotionTransitionCommand command(
            ModelPromotionAggregate current,
            ModelPromotionLifecycleState target,
            String assessmentId,
            int sequence,
            ExactModelArtifactIdentity identity,
            ModelGovernanceScope scope,
            long revision,
            ModelPromotionLifecycleState sourceState
    ) {
        return new ModelPromotionTransitionCommand(
                identity,
                scope,
                current.intendedUse(),
                sourceState,
                revision,
                target,
                "operation-" + sequence,
                new ModelPromotionDecisionReference(
                        "decision-" + sequence,
                        new ModelPromotionDecisionReference.ProposalReference("proposal-" + sequence),
                        assessmentId == null ? null : new ModelPromotionDecisionReference.EvidenceBundleReference(
                                "evidence-" + sequence,
                                "e".repeat(64)
                        ),
                        assessmentId == null ? null : new ModelPromotionDecisionReference.AssessmentReference(
                                assessmentId,
                                "a".repeat(64)
                        ),
                        new ModelPromotionDecisionReference.ApprovalReference("approval-" + sequence)
                )
        );
    }

    private ModelPromotionAggregate candidate(ModelGovernanceScope scope) {
        return ModelPromotionAggregate.registerCandidate(
                identity("a".repeat(64)),
                scope,
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING
        );
    }

    private ExactModelArtifactIdentity identity(String sha256) {
        return new ExactModelArtifactIdentity("fraud-model", "2026.10", "feature-contract-v2", sha256);
    }

    private ModelGovernanceScope scope(ModelGovernanceEnvironment environment) {
        return new ModelGovernanceScope(ModelGovernanceScope.CURRENT_INSTITUTION, environment);
    }
}
