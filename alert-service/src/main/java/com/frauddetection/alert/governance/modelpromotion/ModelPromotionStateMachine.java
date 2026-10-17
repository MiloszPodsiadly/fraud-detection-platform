package com.frauddetection.alert.governance.modelpromotion;

import java.util.Map;
import java.util.Set;

public final class ModelPromotionStateMachine {

    private static final Map<ModelPromotionLifecycleState, Set<ModelPromotionLifecycleState>> ALLOWED = Map.of(
            ModelPromotionLifecycleState.CANDIDATE, Set.of(
                    ModelPromotionLifecycleState.CHALLENGER,
                    ModelPromotionLifecycleState.REJECTED
            ),
            ModelPromotionLifecycleState.CHALLENGER, Set.of(
                    ModelPromotionLifecycleState.SHADOW_APPROVED,
                    ModelPromotionLifecycleState.REJECTED
            ),
            ModelPromotionLifecycleState.SHADOW_APPROVED, Set.of(
                    ModelPromotionLifecycleState.PROMOTION_RECOMMENDED,
                    ModelPromotionLifecycleState.REJECTED
            ),
            ModelPromotionLifecycleState.PROMOTION_RECOMMENDED, Set.of(
                    ModelPromotionLifecycleState.CHAMPION,
                    ModelPromotionLifecycleState.REJECTED
            ),
            ModelPromotionLifecycleState.CHAMPION, Set.of(ModelPromotionLifecycleState.RETIRED)
    );

    private static final Set<ModelPromotionLifecycleState> ASSESSMENT_REQUIRED = Set.of(
            ModelPromotionLifecycleState.SHADOW_APPROVED,
            ModelPromotionLifecycleState.PROMOTION_RECOMMENDED,
            ModelPromotionLifecycleState.CHAMPION
    );

    public boolean canTransition(ModelPromotionLifecycleState source, ModelPromotionLifecycleState target) {
        return ALLOWED.getOrDefault(source, Set.of()).contains(target);
    }

    public boolean isTerminal(ModelPromotionLifecycleState state) {
        return state == ModelPromotionLifecycleState.RETIRED
                || state == ModelPromotionLifecycleState.REJECTED;
    }

    public ModelPromotionAggregate transition(
            ModelPromotionAggregate current,
            ModelPromotionTransitionCommand command
    ) {
        requireMatchingAggregate(current, command);
        if (!canTransition(current.lifecycleState(), command.requestedTargetState())) {
            throw new ModelPromotionPersistenceConflictException("Unsupported model promotion transition: "
                    + current.lifecycleState() + " -> " + command.requestedTargetState());
        }
        if (ASSESSMENT_REQUIRED.contains(command.requestedTargetState())
                && (!command.decisionReference().hasEvidenceBundle()
                || !command.decisionReference().hasAssessment())) {
            throw new ModelPromotionPersistenceConflictException(
                    "Transition requires immutable evidence bundle and assessment references"
            );
        }
        return new ModelPromotionAggregate(
                current.artifactIdentity(),
                current.governanceScope(),
                current.intendedUse(),
                command.requestedTargetState(),
                Math.incrementExact(current.revision()),
                command.decisionReference().decisionId()
        );
    }

    public void validateProposal(
            ModelPromotionAggregate current,
            ModelPromotionTransitionProposal proposal
    ) {
        requireMatchingAggregate(
                current,
                proposal.artifactIdentity(),
                proposal.governanceScope(),
                proposal.intendedUse(),
                proposal.sourceState(),
                proposal.expectedRevision()
        );
        if (!canTransition(current.lifecycleState(), proposal.requestedTargetState())) {
            throw new ModelPromotionPersistenceConflictException("Unsupported model promotion transition: "
                    + current.lifecycleState() + " -> " + proposal.requestedTargetState());
        }
    }

    private void requireMatchingAggregate(
            ModelPromotionAggregate current,
            ModelPromotionTransitionCommand command
    ) {
        requireMatchingAggregate(
                current,
                command.artifactIdentity(),
                command.governanceScope(),
                command.intendedUse(),
                command.sourceState(),
                command.expectedRevision()
        );
    }

    private void requireMatchingAggregate(
            ModelPromotionAggregate current,
            com.frauddetection.alert.governance.ExactModelArtifactIdentity artifactIdentity,
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse,
            ModelPromotionLifecycleState sourceState,
            long expectedRevision
    ) {
        if (!current.artifactIdentity().equals(artifactIdentity)) {
            throw new ModelPromotionPersistenceConflictException(
                    "Transition artifact identity does not match the authoritative aggregate"
            );
        }
        if (!current.governanceScope().equals(governanceScope)) {
            throw new ModelPromotionPersistenceConflictException(
                    "Transition governance scope does not match the authoritative aggregate"
            );
        }
        if (current.intendedUse() != intendedUse) {
            throw new ModelPromotionPersistenceConflictException(
                    "Transition intended use does not match the authoritative aggregate"
            );
        }
        if (current.lifecycleState() != sourceState) {
            throw new ModelPromotionPersistenceConflictException(
                    "Transition source state does not match the authoritative aggregate"
            );
        }
        if (current.revision() != expectedRevision) {
            throw new ModelPromotionPersistenceConflictException(
                    "Transition revision does not match the authoritative aggregate"
            );
        }
    }
}
