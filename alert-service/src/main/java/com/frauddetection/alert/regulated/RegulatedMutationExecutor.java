package com.frauddetection.alert.regulated;

public interface RegulatedMutationExecutor {

    RegulatedMutationModelVersion modelVersion();

    <R, S> RegulatedMutationResult<S> execute(
            RegulatedMutationCommand<R, S> command,
            String idempotencyKey,
            RegulatedMutationCommandDocument document
    );
}
