package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.regulated.chaos.Fdp38LiveRuntimeCheckpoint;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosResult;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosWindow;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("fdp38")
@Tag("live-runtime-checkpoint-chaos")
@Tag("docker-chaos")
@Tag("evidence-gated-finalize")
@Tag("integration")
@EnabledIf("fdp38LiveCheckpointEnabled")
class RegulatedMutationLiveCheckpointBeforeFdp29FinalizeIT extends AbstractRegulatedMutationFdp38LiveCheckpointIT {

    @Test
    void beforeFdp29LocalFinalizeLiveKillDoesNotClaimFinality() throws Exception {
        String alertId = "alert-fdp38-fdp29-finalize";
        String idempotencyKey = "idem-fdp38-fdp29-finalize";
        alertRepository.save(alert(alertId));

        chaosHarness.startFixture(
                "before-fdp29-local-finalize",
                Fdp38LiveRuntimeCheckpoint.BEFORE_FDP29_LOCAL_FINALIZE,
                idempotencyKey,
                evidenceGatedArgs()
        );

        var submitFuture = chaosHarness.submitDecisionAsync(alertId, idempotencyKey, decisionJson("before-fdp29-finalize"));
        Document barrier = awaitBarrier(idempotencyKey, Fdp38LiveRuntimeCheckpoint.BEFORE_FDP29_LOCAL_FINALIZE);
        RegulatedMutationCommandDocument command = awaitCommand(idempotencyKey);
        assertThat(barrier.getString("mutation_command_id")).isEqualTo(command.getId());
        assertThat(command.mutationModelVersionOrLegacy()).isEqualTo(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZING);

        chaosHarness.killFixtureAbruptly();
        submitFuture.handle((response, failure) -> null).get(10, TimeUnit.SECONDS);
        chaosHarness.restartFixture("after-fdp29-finalize-kill", evidenceGatedArgs());

        RegulatedMutationChaosResult result = chaosHarness.collectEvidence(
                scenario("before-fdp29-local-finalize", RegulatedMutationChaosWindow.BEFORE_FDP29_LOCAL_FINALIZE, command),
                Fdp38LiveRuntimeCheckpoint.BEFORE_FDP29_LOCAL_FINALIZE,
                chaosHarness.inspectByCommandId(command.getId()),
                null
        );

        RegulatedMutationCommandDocument persistedCommand = commandRepository.findById(command.getId()).orElseThrow();
        AlertDocument persistedAlert = alertRepository.findById(alertId).orElseThrow();
        assertFixtureKillAndRestart(result);
        assertThat(persistedCommand.getLocalCommitMarker()).isNull();
        assertThat(persistedCommand.getResponseSnapshot()).isNull();
        assertThat(persistedCommand.getPublicStatus()).isNotIn(
                SubmitDecisionOperationStatus.FINALIZED_VISIBLE,
                SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED
        );
        assertThat(persistedAlert.getAlertStatus()).isEqualTo(com.frauddetection.common.events.enums.AlertStatus.OPEN);
        assertThat(persistedAlert.getAnalystDecision()).isNull();
        assertThat(result.outboxRecords()).isZero();
        assertThat(result.successAuditEvents()).isZero();
        assertThat(result.businessMutationCount()).isZero();
    }
}
