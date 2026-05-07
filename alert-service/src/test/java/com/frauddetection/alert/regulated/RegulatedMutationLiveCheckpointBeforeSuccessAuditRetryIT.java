package com.frauddetection.alert.regulated;

import com.frauddetection.alert.regulated.chaos.Fdp38LiveRuntimeCheckpoint;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosResult;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosWindow;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("fdp38")
@Tag("live-runtime-checkpoint-chaos")
@Tag("docker-chaos")
@Tag("integration")
@EnabledIf("fdp38LiveCheckpointEnabled")
class RegulatedMutationLiveCheckpointBeforeSuccessAuditRetryIT extends AbstractRegulatedMutationFdp38LiveCheckpointIT {

    @Test
    void beforeSuccessAuditRetryLiveKillDoesNotDuplicateBusinessMutationOrOutbox() throws Exception {
        String alertId = "alert-fdp38-success-audit-retry";
        String commandId = "command-fdp38-success-audit-retry";
        String idempotencyKey = "idem-fdp38-success-audit-retry";
        seedSuccessAuditPendingCommand(alertId, commandId, idempotencyKey);

        chaosHarness.startFixture(
                "before-success-audit-retry",
                Fdp38LiveRuntimeCheckpoint.BEFORE_SUCCESS_AUDIT_RETRY,
                idempotencyKey,
                List.of()
        );

        var submitFuture = chaosHarness.submitDecisionAsync(alertId, idempotencyKey, decisionJson("before-success-audit-retry"));
        Document barrier = awaitBarrier(idempotencyKey, Fdp38LiveRuntimeCheckpoint.BEFORE_SUCCESS_AUDIT_RETRY);
        RegulatedMutationCommandDocument command = awaitCommand(idempotencyKey);
        assertThat(barrier.getString("mutation_command_id")).isEqualTo(command.getId());
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.SUCCESS_AUDIT_PENDING);
        assertThat(command.getResponseSnapshot()).isNotNull();

        chaosHarness.killFixtureAbruptly();
        submitFuture.handle((response, failure) -> null).get(10, TimeUnit.SECONDS);
        chaosHarness.restartFixture("after-success-audit-retry-kill", List.of());

        RegulatedMutationChaosResult result = chaosHarness.collectEvidence(
                scenario("before-success-audit-retry", RegulatedMutationChaosWindow.BEFORE_SUCCESS_AUDIT_RETRY, command),
                Fdp38LiveRuntimeCheckpoint.BEFORE_SUCCESS_AUDIT_RETRY,
                chaosHarness.inspectByCommandId(command.getId()),
                null
        );

        RegulatedMutationCommandDocument persistedCommand = commandRepository.findById(command.getId()).orElseThrow();
        assertFixtureKillAndRestart(result);
        assertThat(persistedCommand.getState()).isEqualTo(RegulatedMutationState.SUCCESS_AUDIT_PENDING);
        assertThat(result.businessMutationCount()).isEqualTo(1L);
        assertThat(result.outboxRecords()).isEqualTo(1L);
        assertThat(result.successAuditEvents()).isZero();
    }
}
