package com.frauddetection.alert.regulated;

public record RegulatedMutationResult<S>(
        RegulatedMutationState state,
        S response
) {
}
