package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.RegulatedMutationLocalAuditPhaseWriter;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegulatedMutationCheckpointRenewalExecutionTest {

    @Test
    void evidenceFinalizeDoesNotRunAfterFailedCheckpoint() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument document = fixture.document(RegulatedMutationState.EVIDENCE_PREPARED);
        document.setAttemptedAuditRecorded(true);
        when(fixture.commandRepository.findById("command-1")).thenReturn(Optional.of(document));
        when(fixture.commandRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(document));
        when(fixture.claimService.claim(any(), eq("idem-1"))).thenReturn(Optional.of(
                fixture.token(RegulatedMutationState.EVIDENCE_PREPARED)
        ));
        when(fixture.replayResolver.resolve(any(), any())).thenReturn(RegulatedMutationReplayDecision.none());
        when(fixture.evidencePreconditionEvaluator.evaluate(any(), any()))
                .thenReturn(EvidencePreconditionResult.satisfied(List.of(), List.of()));
        when(fixture.checkpointRenewalService.afterEvidencePreparedBeforeFinalize(any(), any()))
                .thenThrow(new RegulatedMutationCheckpointRenewalException(
                        RegulatedMutationRenewalCheckpoint.AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE,
                        RegulatedMutationLeaseRenewalReason.EXPIRED_LEASE
                ));
        AtomicInteger businessMutations = new AtomicInteger();

        assertThatThrownBy(() -> fixture.executor().execute(command(businessMutations), "idem-1", document))
                .isInstanceOf(RegulatedMutationCheckpointRenewalException.class);

        assertThat(businessMutations).hasValue(0);
        verify(fixture.localAuditPhaseWriter, never()).recordSuccessPhase(any(), any(), any());
    }

    @Test
    void productionExecutorRejectsMissingCheckpointRenewalService() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.executor(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Production regulated mutation wiring requires checkpoint renewal service.");
    }

    private RegulatedMutationCommand<String, String> command(AtomicInteger businessMutations) {
        return new RegulatedMutationCommand<>(
                "idem-1",
                "actor-1",
                "alert-1",
                AuditResourceType.ALERT,
                AuditAction.SUBMIT_ANALYST_DECISION,
                "corr-1",
                "request-hash-1",
                context -> {
                    businessMutations.incrementAndGet();
                    return "result";
                },
                (result, state) -> "response-" + state,
                response -> new RegulatedMutationResponseSnapshot(
                        "alert-1", null, null, "event-1", Instant.parse("2026-05-05T08:00:00Z"), null
                ),
                snapshot -> "restored",
                state -> "status-" + state,
                null,
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
        );
    }

    private static final class Fixture {
        private final RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        private final RegulatedMutationAuditPhaseService auditPhaseService = mock(RegulatedMutationAuditPhaseService.class);
        private final AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        private final RegulatedMutationClaimService claimService = mock(RegulatedMutationClaimService.class);
        private final RegulatedMutationReplayResolver replayResolver = mock(RegulatedMutationReplayResolver.class);
        private final RegulatedMutationFencedCommandWriter fencedCommandWriter = mock(RegulatedMutationFencedCommandWriter.class);
        private final EvidencePreconditionEvaluator evidencePreconditionEvaluator = mock(EvidencePreconditionEvaluator.class);
        private final RegulatedMutationLocalAuditPhaseWriter localAuditPhaseWriter = mock(RegulatedMutationLocalAuditPhaseWriter.class);
        private final RegulatedMutationCheckpointRenewalService checkpointRenewalService =
                mock(RegulatedMutationCheckpointRenewalService.class);

        private Fixture() {
            when(localAuditPhaseWriter.withChainLock(any())).thenAnswer(invocation ->
                    ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
        }

        private EvidenceGatedFinalizeExecutor executor() {
            return executor(checkpointRenewalService);
        }

        private EvidenceGatedFinalizeExecutor executor(
                RegulatedMutationCheckpointRenewalService checkpointRenewalService
        ) {
            return new EvidenceGatedFinalizeExecutor(
                    commandRepository,
                    mongoTemplate,
                    auditPhaseService,
                    metrics,
                    new RegulatedMutationTransactionRunner(RegulatedMutationTransactionMode.REQUIRED, null),
                    new RegulatedMutationPublicStatusMapper(),
                    evidencePreconditionEvaluator,
                    localAuditPhaseWriter,
                    claimService,
                    new RegulatedMutationConflictPolicy(),
                    replayResolver,
                    fencedCommandWriter,
                    checkpointRenewalService
            );
        }

        private RegulatedMutationCommandDocument document(RegulatedMutationState state) {
            RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
            document.setId("command-1");
            document.setIdempotencyKey("idem-1");
            document.setMutationModelVersion(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
            document.setRevision(0L);
            document.setState(state);
            document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
            document.setLeaseOwner("owner-1");
            document.setLeaseExpiresAt(Instant.parse("2026-05-05T08:00:30Z"));
            document.setCreatedAt(Instant.parse("2026-05-05T08:00:00Z"));
            return document;
        }

        private RegulatedMutationClaimToken token(RegulatedMutationState state) {
            return new RegulatedMutationClaimToken(
                    "command-1",
                    "owner-1",
                    Instant.parse("2026-05-05T08:00:30Z"),
                    Instant.parse("2026-05-05T08:00:00Z"),
                    1,
                    RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                    state,
                    RegulatedMutationExecutionStatus.PROCESSING
            );
        }
    }
}
