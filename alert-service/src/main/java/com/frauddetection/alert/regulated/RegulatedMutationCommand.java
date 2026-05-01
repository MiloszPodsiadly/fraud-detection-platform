package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;

public record RegulatedMutationCommand<R, S>(
        String idempotencyKey,
        String actorId,
        String resourceId,
        AuditResourceType resourceType,
        AuditAction action,
        String correlationId,
        String requestHash,
        BusinessMutation<R> mutation,
        RegulatedMutationResponseMapper<R, S> responseMapper,
        RegulatedMutationResponseSnapshotter<S> responseSnapshotter,
        RegulatedMutationResponseRestorer<S> responseRestorer,
        RegulatedMutationStatusResponseFactory<S> statusResponseFactory,
        RegulatedMutationIntent intent
) {
    public RegulatedMutationCommand(
            String idempotencyKey,
            String actorId,
            String resourceId,
            AuditResourceType resourceType,
            AuditAction action,
            String correlationId,
            String requestHash,
            BusinessMutation<R> mutation,
            RegulatedMutationResponseMapper<R, S> responseMapper,
            RegulatedMutationResponseSnapshotter<S> responseSnapshotter,
            RegulatedMutationResponseRestorer<S> responseRestorer,
            RegulatedMutationStatusResponseFactory<S> statusResponseFactory
    ) {
        this(
                idempotencyKey,
                actorId,
                resourceId,
                resourceType,
                action,
                correlationId,
                requestHash,
                mutation,
                responseMapper,
                responseSnapshotter,
                responseRestorer,
                statusResponseFactory,
                null
        );
    }
}
