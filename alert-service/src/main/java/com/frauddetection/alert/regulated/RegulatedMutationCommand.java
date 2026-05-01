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
        RegulatedMutationResponseSnapshotter<S> responseSnapshotter
) {
}
