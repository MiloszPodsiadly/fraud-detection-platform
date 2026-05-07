package com.frauddetection.alert.regulated;

import com.frauddetection.alert.persistence.AlertDocument;
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
class RegulatedMutationLiveCheckpointAfterAttemptedAuditIT extends AbstractRegulatedMutationFdp38LiveCheckpointIT {

    @Test
    void afterAttemptedAuditBeforeBusinessMutationLiveKillPreservesAttemptedAuditOnly() throws Exception {
        String alertId = "alert-fdp38-after-attempted";
        String idempotencyKey = "idem-fdp38-after-attempted";
        alertRepository.save(alert(alertId));

        chaosHarness.startFixture(
                "after-attempted-before-business",
                Fdp38LiveRuntimeCheckpoint.AFTER_ATTEMPTED_AUDIT_BEFORE_BUSINESS_MUTATION,
                idempotencyKey,
                List.of()
        );

        var submitFuture = chaosHarness.submitDecisionAsync(alertId, idempotencyKey, decisionJson("after-attempted"));
        Document barrier = awaitBarrier(
                idempotencyKey,
                Fdp38LiveRuntimeCheckpoint.AFTER_ATTEMPTED_AUDIT_BEFORE_BUSINESS_MUTATION
        );
        RegulatedMutationCommandDocument command = awaitCommand(idempotencyKey);
        assertThat(barrier.getString("mutation_command_id")).isEqualTo(command.getId());
        assertThat(command.isAttemptedAuditRecorded()).isTrue();
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.AUDIT_ATTEMPTED);

        chaosHarness.killFixtureAbruptly();
        submitFuture.handle((response, failure) -> null).get(10, TimeUnit.SECONDS);
        chaosHarness.restartFixture("after-attempted-restart", List.of());

        RegulatedMutationChaosResult result = chaosHarness.collectEvidence(
                scenario(
                        "after-attempted-audit-before-business",
                        RegulatedMutationChaosWindow.AFTER_ATTEMPTED_AUDIT_BEFORE_BUSINESS_MUTATION,
                        command
                ),
                Fdp38LiveRuntimeCheckpoint.AFTER_ATTEMPTED_AUDIT_BEFORE_BUSINESS_MUTATION,
                chaosHarness.inspectByCommandId(command.getId()),
                null
        );

        RegulatedMutationCommandDocument persistedCommand = commandRepository.findById(command.getId()).orElseThrow();
        AlertDocument persistedAlert = alertRepository.findById(alertId).orElseThrow();
        assertFixtureKillAndRestart(result);
        assertNoCommittedSuccess(persistedCommand, persistedAlert, result);
        assertThat(result.attemptedAuditEvents()).isEqualTo(1L);
    }
}
