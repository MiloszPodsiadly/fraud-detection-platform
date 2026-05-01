package com.frauddetection.alert.regulated;

@FunctionalInterface
public interface RegulatedMutationResponseRestorer<S> {
    S restore(RegulatedMutationResponseSnapshot snapshot);
}
