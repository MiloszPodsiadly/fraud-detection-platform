package com.frauddetection.alert.api;

public interface RegulatedMutationPublicStatusProjection<T> {
    T withPublicStatus(SubmitDecisionOperationStatus status);
}
