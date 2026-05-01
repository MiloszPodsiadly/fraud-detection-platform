package com.frauddetection.alert.regulated;

@FunctionalInterface
public interface RegulatedMutationResponseMapper<R, S> {
    S response(R result, RegulatedMutationState state);
}
