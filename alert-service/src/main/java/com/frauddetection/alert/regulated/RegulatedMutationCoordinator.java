package com.frauddetection.alert.regulated;

public interface RegulatedMutationCoordinator {
    <R, S> RegulatedMutationResult<S> commit(RegulatedMutationCommand<R, S> command);
}
