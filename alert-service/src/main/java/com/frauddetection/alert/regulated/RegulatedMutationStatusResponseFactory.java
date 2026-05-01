package com.frauddetection.alert.regulated;

@FunctionalInterface
public interface RegulatedMutationStatusResponseFactory<S> {
    S response(RegulatedMutationState state);
}
