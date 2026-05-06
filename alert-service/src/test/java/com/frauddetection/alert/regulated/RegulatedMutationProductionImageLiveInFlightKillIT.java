package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosResult;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosScenario;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosWindow;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationProofLevel;
import com.frauddetection.common.events.enums.AlertStatus;
import org.bson.Document;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("fdp37")
@Tag("production-image-chaos")
@Tag("docker-chaos")
@Tag("live-in-flight-chaos")
@Tag("integration")
@EnabledIf("productionImageChaosEnabled")
class RegulatedMutationProductionImageLiveInFlightKillIT extends AbstractRegulatedMutationProductionImageChaosIT {

    @Test
    void productionImageLiveInFlightBeforeBusinessMutationKillDoesNotCommitOrPublish() throws Exception {
        Assumptions.assumeTrue(
                Boolean.getBoolean("fdp37.live-in-flight.enabled"),
                "FDP-37 live in-flight production-image proof requires an explicit test-fixture image with test-only checkpoint support."
        );
        String alertId = "alert-fdp37-live-inflight";
        String idempotencyKey = "idem-fdp37-live-inflight";
        alertRepository.save(alert(alertId));

        chaosHarness.startAlertService("live-before-business", List.of(
                "--spring.profiles.active=test,fdp36-live-in-flight",
                "--app.fdp36.live-in-flight.idempotency-key=" + idempotencyKey,
                "--app.regulated-mutation.lease-duration=PT5S"
        ));

        var submitFuture = chaosHarness.submitDecisionAsync(alertId, idempotencyKey, """
                {
                  "analystId": "fdp37-analyst",
                  "decision": "CONFIRMED_FRAUD",
                  "decisionReason": "production image live in-flight kill proof",
                  "tags": ["fdp37", "production-image-chaos"],
                  "decisionMetadata": {"proof": "fdp37"}
                }
                """);

        Document barrier = awaitBarrier(idempotencyKey);
        assertThat(barrier.getString("state")).isEqualTo("BLOCKED_BEFORE_BUSINESS_MUTATION");
        RegulatedMutationCommandDocument command = awaitCommand(idempotencyKey);
        assertThat(command.getLeaseOwner()).isNotBlank();
        assertThat(command.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.PROCESSING);
        assertThat(command.getState()).isIn(RegulatedMutationState.AUDIT_ATTEMPTED, RegulatedMutationState.BUSINESS_COMMITTING);

        chaosHarness.killAlertServiceContainerAbruptly();
        submitFuture.handle((response, failure) -> null).get(10, TimeUnit.SECONDS);
        chaosHarness.restartAlertService("live-after-restart", List.of());

        RegulatedMutationChaosScenario scenario = new RegulatedMutationChaosScenario(
                "live-inflight-before-business",
                RegulatedMutationChaosWindow.LIVE_IN_FLIGHT_BEFORE_BUSINESS_MUTATION,
                command.getId(),
                idempotencyKey,
                ignored -> {
                }
        );
        RegulatedMutationChaosResult result = chaosHarness.collectEvidence(
                scenario,
                chaosHarness.inspectByCommandId(command.getId()),
                null,
                EnumSet.of(
                        RegulatedMutationProofLevel.PRODUCTION_IMAGE_CONTAINER_KILL,
                        RegulatedMutationProofLevel.PRODUCTION_IMAGE_RESTART_API_PROOF,
                        RegulatedMutationProofLevel.LIVE_IN_FLIGHT_REQUEST_KILL,
                        RegulatedMutationProofLevel.API_PERSISTED_STATE_PROOF
                )
        );

        AlertDocument persistedAlert = alertRepository.findById(alertId).orElseThrow();
        RegulatedMutationCommandDocument persistedCommand = commandRepository.findById(command.getId()).orElseThrow();
        assertThat(result.targetKilled()).isTrue();
        assertThat(result.targetRestarted()).isTrue();
        assertThat(result.killedTargetName()).contains("alert-service");
        assertThat(result.restartedTargetName()).contains("alert-service");
        assertThat(result.restartedTargetId()).isNotEqualTo(result.killedTargetId());
        assertThat(result.proofLevel()).isEqualTo(RegulatedMutationProofLevel.PRODUCTION_IMAGE_CONTAINER_KILL);
        assertThat(persistedCommand.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.PROCESSING);
        assertThat(persistedCommand.getResponseSnapshot()).isNull();
        assertThat(persistedCommand.getPublicStatus()).isNotIn(
                SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING,
                SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_CONFIRMED,
                SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED
        );
        assertThat(persistedAlert.getAlertStatus()).isEqualTo(AlertStatus.OPEN);
        assertThat(persistedAlert.getAnalystDecision()).isNull();
        assertThat(result.outboxRecords()).isZero();
        assertThat(result.successAuditEvents()).isZero();
    }

    private Document awaitBarrier(String idempotencyKey) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            Document barrier = mongoTemplate.getCollection("fdp36_live_inflight_barriers")
                    .find(new Document("_id", idempotencyKey))
                    .first();
            if (barrier != null) {
                return barrier;
            }
            sleep();
        }
        throw new AssertionError("FDP-37 live in-flight production-image barrier was not reached.");
    }

    private RegulatedMutationCommandDocument awaitCommand(String idempotencyKey) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            RegulatedMutationCommandDocument command = commandRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (command != null) {
                return command;
            }
            sleep();
        }
        throw new AssertionError("FDP-37 live in-flight production-image command was not persisted.");
    }

    private void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for FDP-37 live in-flight proof", exception);
        }
    }
}
