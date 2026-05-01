package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;

public record RegulatedMutationDefinition(AuditAction action, AuditResourceType resourceType) {
}
