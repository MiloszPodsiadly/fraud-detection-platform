package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.RegulatedMutationLocalAuditPhaseWriter;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
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
    void legacyBusinessMutationDoesNotRunAfterFailedCheckpoint() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument document = fixture.document(
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.AUDIT_ATTEMPTED
        );
        document.setAttemptedAuditRecorded(true);
        when(fixture.commandRepository.findById("command-1")).thenReturn(Optional.of(document));
        when(fixture.commandRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(document));
        when(fixture.claimService.claim(any(), eq("idem-1"))).thenReturn(Optional.of(fixture.token(
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.AUDIT_ATTEMPTED
        )));
        when(fixture.replayResolver.resolve(any(), any())).thenReturn(RegulatedMutationReplayDecision.none());
        when(fixture.checkpointRenewalService.beforeLegacyBusinessCommit(any(), any()))
                .thenThrow(new RegulatedMutationCheckpointRenewalException(
                        RegulatedMutationRenewalCheckpoint.BEFORE_LEGACY_BUSINESS_COMMIT,
                        RegulatedMutationLeaseRenewalReason.STALE_OWNER
                ));
        AtomicInteger businessMutations = new AtomicInteger();

        assertThatThrownBy(() -> fixture.legacyExecutor().execute(command(businessMutations), "idem-1", document))
                .isInstanceOf(RegulatedMutationCheckpointRenewalException.class);

        assertThat(businessMutations).hasValue(0);
        verify(fixture.checkpointRenewalService).beforeLegacyBusinessCommit(any(), any());
        verify(fixture.auditPhaseService, never()).recordPhase(any(), any(), any(), eq(AuditOutcome.SUCCESS), any());
    }

    @Test
    void evidenceFinalizeDoesNotRunAfterFailedCheckpoint() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument document = fixture.document(
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                RegulatedMutationState.EVIDENCE_PREPARED
        );
        document.setAttemptedAuditRecorded(true);
        when(fixture.commandRepository.findById("command-1")).thenReturn(Optional.of(document));
        when(fixture.commandRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(document));
        when(fixture.claimService.claim(any(), eq("idem-1"))).thenReturn(Optional.of(fixture.token(
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                RegulatedMutationState.EVIDENCE_PREPARED
        )));
        when(fixture.replayResolver.resolve(any(), any())).thenReturn(RegulatedMutationReplayDecision.none());
        when(fixture.evidencePreconditionEvaluator.evaluate(any(), any()))
                .thenReturn(EvidencePreconditionResult.satisfied(List.of(), List.of()));
        when(fixture.checkpointRenewalService.afterEvidencePreparedBeforeFinalize(any(), any()))
                .thenThrow(new RegulatedMutationCheckpointRenewalException(
                        RegulatedMutationRenewalCheckpoint.AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE,
                        RegulatedMutationLeaseRenewalReason.EXPIRED_LEASE
                ));
        AtomicInteger businessMutations = new AtomicInteger();

        assertThatThrownBy(() -> fixture.evidenceExecutor().execute(command(businessMutations), "idem-1", document))
                .isInstanceOf(RegulatedMutationCheckpointRenewalException.class);

        assertThat(businessMutations).hasValue(0);
        verify(fixture.localAuditPhaseWriter, never()).recordSuccessPhase(any(), any(), any());
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
                        "alert-1",
                        null,
                        null,
                        "event-1",
                        Instant.parse("2026-05-05T08:00:00Z"),
                        null
                ),
                snapshot -> "restored",
                state -> "status-" + state
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

        private LegacyRegulatedMutationExecutor legacyExecutor() {
            return new LegacyRegulatedMutationExecutor(
                    commandRepository,
                    mongoTemplate,
                    auditPhaseService,
                    mock(com.frauddetection.alert.audit.AuditDegradationService.class),
                    metrics,
                    new RegulatedMutationTransactionRunner(RegulatedMutationTransactionMode.OFF, null),
                    new RegulatedMutationPublicStatusMapper(),
                    false,
                    claimService,
                    new RegulatedMutationConflictPolicy(),
                    replayResolver,
                    fencedCommandWriter,
                    checkpointRenewalService
            );
        }

        private EvidenceGatedFinalizeExecutor evidenceExecutor() {
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

        private RegulatedMutationCommandDocument document(
                RegulatedMutationModelVersion modelVersion,
                RegulatedMutationState state
        ) {
            RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
            document.setId("command-1");
            document.setIdempotencyKey("idem-1");
            document.setMutationModelVersion(modelVersion);
            document.setState(state);
            document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
            document.setLeaseOwner("owner-1");
            document.setLeaseExpiresAt(Instant.parse("2026-05-05T08:00:30Z"));
            document.setCreatedAt(Instant.parse("2026-05-05T08:00:00Z"));
            return document;
        }

        private RegulatedMutationClaimToken token(
                RegulatedMutationModelVersion modelVersion,
                RegulatedMutationState state
        ) {
            return new RegulatedMutationClaimToken(
                    "command-1",
                    "owner-1",
                    Instant.parse("2026-05-05T08:00:30Z"),
                    Instant.parse("2026-05-05T08:00:00Z"),
                    1,
                    modelVersion,
                    state,
                    RegulatedMutationExecutionStatus.PROCESSING
            );
        }
    }
}
