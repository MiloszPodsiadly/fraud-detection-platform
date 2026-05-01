package com.frauddetection.alert.regulated;

@FunctionalInterface
public interface RegulatedMutationResponseSnapshotter<S> {
    RegulatedMutationResponseSnapshot snapshot(S response);
}
