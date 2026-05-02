package com.frauddetection.alert.regulated;

@FunctionalInterface
public interface BusinessMutation<R> {
    R execute(RegulatedMutationExecutionContext context);
}
