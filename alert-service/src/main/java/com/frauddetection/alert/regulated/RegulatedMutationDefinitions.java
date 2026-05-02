package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;

import java.util.List;

public final class RegulatedMutationDefinitions {

    private static final List<RegulatedMutationDefinition> DEFINITIONS = List.of(
            new RegulatedMutationDefinition(AuditAction.SUBMIT_ANALYST_DECISION, AuditResourceType.ALERT),
            new RegulatedMutationDefinition(AuditAction.UPDATE_FRAUD_CASE, AuditResourceType.FRAUD_CASE),
            new RegulatedMutationDefinition(AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION, AuditResourceType.DECISION_OUTBOX),
            new RegulatedMutationDefinition(AuditAction.ACK_TRUST_INCIDENT, AuditResourceType.TRUST_INCIDENT),
            new RegulatedMutationDefinition(AuditAction.RESOLVE_TRUST_INCIDENT, AuditResourceType.TRUST_INCIDENT),
            new RegulatedMutationDefinition(AuditAction.REFRESH_TRUST_INCIDENTS, AuditResourceType.TRUST_INCIDENT)
    );

    private RegulatedMutationDefinitions() {
    }

    public static List<RegulatedMutationDefinition> all() {
        return DEFINITIONS;
    }
}
