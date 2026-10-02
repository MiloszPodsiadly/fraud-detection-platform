package com.frauddetection.alert.regulated;

import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.regulated.chaos.LiveRuntimeCheckpoint;
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
@EnabledIf("liveCheckpointEnabled")
class RegulatedMutationLiveCheckpointBeforeEvidenceGatedFinalizeIT
        extends AbstractRegulatedMutationLiveCheckpointIT {

    @Test
    void killBeforeEvidenceGatedFinalizeDoesNotClaimFinality() throws Exception {
        String alertId = "alert-fdp38-before-evidence-finalize";
        String idempotencyKey = "idem-fdp38-before-evidence-finalize";
        alertRepository.save(alert(alertId));

        chaosHarness.startFixture(
                "before-evidence-gated-finalize",
                LiveRuntimeCheckpoint.BEFORE_EVIDENCE_GATED_FINALIZE,
                idempotencyKey,
                evidenceGatedArgs()
        );

        var submitFuture = chaosHarness.submitDecisionAsync(alertId, idempotencyKey, decisionJson("before-evidence-finalize"));
        Document barrier = awaitBarrier(idempotencyKey, LiveRuntimeCheckpoint.BEFORE_EVIDENCE_GATED_FINALIZE);
        RegulatedMutationCommandDocument command = awaitCommand(idempotencyKey);
        assertThat(barrier.getString("mutation_command_id")).isEqualTo(command.getId());
        assertThat(command.getMutationModelVersion()).isEqualTo(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZING);

        chaosHarness.killFixtureAbruptly();
        submitFuture.handle((response, failure) -> null).get(10, TimeUnit.SECONDS);
        chaosHarness.restartFixture("after-evidence-finalize-kill", evidenceGatedArgs());

        RegulatedMutationChaosResult result = chaosHarness.collectEvidence(
                scenario("before-evidence-gated-finalize",
                        RegulatedMutationChaosWindow.BEFORE_EVIDENCE_GATED_FINALIZE, command),
                LiveRuntimeCheckpoint.BEFORE_EVIDENCE_GATED_FINALIZE,
                chaosHarness.inspectByCommandId(command.getId()),
                null
        );

        RegulatedMutationCommandDocument persistedCommand = commandRepository.findById(command.getId()).orElseThrow();
        AlertDocument persistedAlert = alertRepository.findById(alertId).orElseThrow();
        assertFixtureKillAndRestart(result);
        assertThat(persistedCommand.getLocalCommitMarker()).isNull();
        assertNoCommittedSuccess(persistedCommand, persistedAlert, result);
        assertThat(result.attemptedAuditEvents()).isEqualTo(1L);
    }
}
