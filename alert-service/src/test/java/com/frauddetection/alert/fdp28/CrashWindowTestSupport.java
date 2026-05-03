package com.frauddetection.alert.fdp28;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.regulated.RegulatedMutationCommandDocument;
import com.frauddetection.alert.regulated.RegulatedMutationExecutionStatus;
import com.frauddetection.alert.regulated.RegulatedMutationState;

import java.time.Instant;

public final class CrashWindowTestSupport {

    private CrashWindowTestSupport() {
    }

    public static RegulatedMutationCommandDocument submitDecisionCommand(RegulatedMutationState state) {
        RegulatedMutationCommandDocument command = new RegulatedMutationCommandDocument();
        command.setId("mutation-1");
        command.setIdempotencyKey("idem-1");
        command.setActorId("principal-7");
        command.setResourceId("alert-1");
        command.setResourceType(AuditResourceType.ALERT.name());
        command.setAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        command.setCorrelationId("corr-1");
        command.setRequestHash("request-hash");
        command.setIdempotencyKeyHash("idem-hash");
        command.setState(state);
        command.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        command.setCreatedAt(Instant.parse("2026-05-03T00:00:00Z"));
        command.setUpdatedAt(Instant.parse("2026-05-03T00:00:00Z"));
        command.setLastHeartbeatAt(Instant.parse("2026-05-03T00:00:00Z"));
        return command;
    }
}
