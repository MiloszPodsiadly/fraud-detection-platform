package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;

public interface RegulatedMutationExecutor {

    RegulatedMutationModelVersion modelVersion();

    boolean supports(AuditAction action, AuditResourceType resourceType);

    <R, S> RegulatedMutationResult<S> execute(
            RegulatedMutationCommand<R, S> command,
            String idempotencyKey,
            RegulatedMutationCommandDocument document
    );
}
