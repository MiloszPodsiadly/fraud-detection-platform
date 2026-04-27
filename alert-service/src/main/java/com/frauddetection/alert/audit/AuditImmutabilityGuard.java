package com.frauddetection.alert.audit;

import org.springframework.stereotype.Component;

@Component
public class AuditImmutabilityGuard {

    public void rejectMutation(String operation) {
        throw new AuditImmutableMutationException(operation == null ? "mutation" : operation);
    }
}
