package com.frauddetection.alert.regulated;

public record RegulatedMutationReplayDecision(
        RegulatedMutationReplayDecisionType type,
        RegulatedMutationState responseState,
        String reason
) {
    public static RegulatedMutationReplayDecision none() {
        return new RegulatedMutationReplayDecision(RegulatedMutationReplayDecisionType.NONE, null, null);
    }

    public static RegulatedMutationReplayDecision of(
            RegulatedMutationReplayDecisionType type,
            RegulatedMutationState responseState,
            String reason
    ) {
        return new RegulatedMutationReplayDecision(type, responseState, reason);
    }

    public boolean present() {
        return type != RegulatedMutationReplayDecisionType.NONE;
    }
}
