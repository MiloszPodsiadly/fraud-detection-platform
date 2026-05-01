package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;

import java.util.Optional;

public interface RegulatedMutationRecoveryStrategy {

    boolean supports(AuditAction action, AuditResourceType resourceType);

    Optional<RegulatedMutationResponseSnapshot> reconstructSnapshot(RegulatedMutationCommandDocument command);

    RecoveryValidationResult validateBusinessState(RegulatedMutationCommandDocument command);
}
