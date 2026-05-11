package com.frauddetection.alert.fraudcase;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.frauddetection.alert.api.FraudCaseDecisionResponse;
import com.frauddetection.alert.api.FraudCaseNoteResponse;
import com.frauddetection.alert.domain.FraudCaseDecisionType;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.idempotency.SharedIdempotencyConflictPolicy;
import com.frauddetection.alert.idempotency.SharedIdempotencyKeyPolicy;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseLifecycleIdempotencyRecordDocument;
import com.frauddetection.alert.persistence.FraudCaseLifecycleIdempotencyRepository;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Fdp44FraudCaseLifecycleReplaySnapshotTest {

    @Test
    void noteReplaySnapshotUsesExplicitDtoAndDoesNotStoreRawIdempotencyInternals() {
        AtomicReference<FraudCaseLifecycleIdempotencyRecordDocument> stored = new AtomicReference<>();
        FraudCaseLifecycleIdempotencyService service = service(stored, FraudCaseLifecycleIdempotencyService.MAX_RESPONSE_SNAPSHOT_BYTES);
        FraudCaseLifecycleIdempotencyCommand command = command("raw-idempotency-key-123", "ADD_FRAUD_CASE_NOTE", "case-1", "request-hash-1");
        FraudCaseNoteResponse response = new FraudCaseNoteResponse(
                "note-1",
                "case-1",
                "Investigator-visible note",
                "analyst-1",
                Instant.parse("2026-05-11T10:00:00Z"),
                true
        );
        AtomicInteger mutationCalls = new AtomicInteger();

        FraudCaseNoteResponse first = service.execute(command, () -> {
            mutationCalls.incrementAndGet();
            return response;
        }, FraudCaseNoteResponse.class);
        FraudCaseNoteResponse replay = service.execute(command, () -> {
            mutationCalls.incrementAndGet();
            return new FraudCaseNoteResponse("note-2", "case-1", "duplicate", "analyst-1", Instant.now(), false);
        }, FraudCaseNoteResponse.class);

        String snapshot = stored.get().getResponsePayloadSnapshot();
        assertThat(first).isEqualTo(response);
        assertThat(replay).isEqualTo(response);
        assertThat(mutationCalls).hasValue(1);
        assertThat(snapshot)
                .contains("\"snapshotType\":\"NOTE\"")
                .contains("\"action\":\"ADD_FRAUD_CASE_NOTE\"")
                .contains("\"caseId\":\"case-1\"")
                .contains("\"noteId\":\"note-1\"")
                .doesNotContain("raw-idempotency-key-123")
                .doesNotContain("idempotencyKey")
                .doesNotContain("idempotencyKeyHash")
                .doesNotContain("requestHash")
                .doesNotContain("request-hash-1")
                .doesNotContain("leaseOwner")
                .doesNotContain("stackTrace")
                .doesNotContain("exception");
    }

    @Test
    void caseAndDecisionReplaySnapshotsUseStableExplicitFields() {
        FraudCaseLifecycleReplaySnapshotMapper mapper = new FraudCaseLifecycleReplaySnapshotMapper();
        Instant completedAt = Instant.parse("2026-05-11T10:00:15Z");
        FraudCaseLifecycleIdempotencyCommand createCommand = command(
                "case-key",
                "CREATE_FRAUD_CASE",
                "CREATE",
                "case-request-hash"
        );
        FraudCaseDocument document = new FraudCaseDocument();
        document.setCaseId("case-1");
        document.setCaseNumber("FC-20260511-ABCDEF12");
        document.setStatus(FraudCaseStatus.OPEN);
        document.setPriority(FraudCasePriority.HIGH);
        document.setRiskLevel(RiskLevel.CRITICAL);
        document.setLinkedAlertIds(List.of("alert-1"));
        document.setCreatedBy("analyst-1");
        document.setReason("Manual investigation");
        document.setCreatedAt(Instant.parse("2026-05-11T09:59:00Z"));
        document.setUpdatedAt(Instant.parse("2026-05-11T10:00:00Z"));

        FraudCaseLifecycleReplaySnapshot caseSnapshot = mapper.toSnapshot(createCommand, document, completedAt);
        FraudCaseDocument restoredCase = mapper.toResponse(caseSnapshot, FraudCaseDocument.class);

        assertThat(caseSnapshot.snapshotType()).isEqualTo(FraudCaseLifecycleReplaySnapshotType.CASE);
        assertThat(caseSnapshot.caseId()).isEqualTo("case-1");
        assertThat(caseSnapshot.status()).isEqualTo(FraudCaseStatus.OPEN);
        assertThat(caseSnapshot.completedAt()).isEqualTo(completedAt);
        assertThat(restoredCase.getCaseId()).isEqualTo(document.getCaseId());
        assertThat(restoredCase.getStatus()).isEqualTo(document.getStatus());

        FraudCaseDecisionResponse decision = new FraudCaseDecisionResponse(
                "decision-1",
                "case-1",
                FraudCaseDecisionType.FRAUD_CONFIRMED,
                "Fraud confirmed by analyst",
                "analyst-1",
                Instant.parse("2026-05-11T10:01:00Z")
        );

        FraudCaseLifecycleReplaySnapshot decisionSnapshot = mapper.toSnapshot(
                command("decision-key", "ADD_FRAUD_CASE_DECISION", "case-1", "decision-request-hash"),
                decision,
                completedAt
        );
        FraudCaseDecisionResponse restoredDecision = mapper.toResponse(decisionSnapshot, FraudCaseDecisionResponse.class);

        assertThat(decisionSnapshot.snapshotType()).isEqualTo(FraudCaseLifecycleReplaySnapshotType.DECISION);
        assertThat(decisionSnapshot.decisionId()).isEqualTo("decision-1");
        assertThat(decisionSnapshot.decisionType()).isEqualTo(FraudCaseDecisionType.FRAUD_CONFIRMED);
        assertThat(restoredDecision).isEqualTo(decision);
    }

    @Test
    void oversizedExplicitReplaySnapshotFailsClosed() {
        AtomicReference<FraudCaseLifecycleIdempotencyRecordDocument> stored = new AtomicReference<>();
        FraudCaseLifecycleIdempotencyService service = service(stored, 8);

        assertThatThrownBy(() -> service.execute(
                command("tiny-snapshot-key", "ADD_FRAUD_CASE_NOTE", "case-1", "request-hash-1"),
                () -> new FraudCaseNoteResponse(
                        "note-1",
                        "case-1",
                        "This response is intentionally too large for the tiny test limit.",
                        "analyst-1",
                        Instant.parse("2026-05-11T10:00:00Z"),
                        false
                ),
                FraudCaseNoteResponse.class
        )).isInstanceOf(FraudCaseIdempotencySnapshotTooLargeException.class);

        assertThat(stored.get().getStatus()).isEqualTo(FraudCaseLifecycleIdempotencyStatus.IN_PROGRESS);
    }

    private FraudCaseLifecycleIdempotencyService service(
            AtomicReference<FraudCaseLifecycleIdempotencyRecordDocument> stored,
            int maxSnapshotBytes
    ) {
        FraudCaseLifecycleIdempotencyRepository repository = mock(FraudCaseLifecycleIdempotencyRepository.class);
        when(repository.findByIdempotencyKeyHash(any())).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.save(any())).thenAnswer(invocation -> {
            FraudCaseLifecycleIdempotencyRecordDocument record = invocation.getArgument(0);
            stored.set(record);
            return record;
        });
        return new FraudCaseLifecycleIdempotencyService(
                repository,
                new SharedIdempotencyKeyPolicy(),
                new FraudCaseLifecycleIdempotencyConflictPolicy(new SharedIdempotencyConflictPolicy()),
                transactionRunner(),
                JsonMapper.builder().addModule(new JavaTimeModule()).build(),
                maxSnapshotBytes,
                Duration.ofHours(24)
        );
    }

    private RegulatedMutationTransactionRunner transactionRunner() {
        RegulatedMutationTransactionRunner transactionRunner = mock(RegulatedMutationTransactionRunner.class);
        when(transactionRunner.runLocalCommit(any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
        return transactionRunner;
    }

    private FraudCaseLifecycleIdempotencyCommand command(
            String idempotencyKey,
            String action,
            String caseIdScope,
            String requestHash
    ) {
        return new FraudCaseLifecycleIdempotencyCommand(
                idempotencyKey,
                action,
                "analyst-1",
                caseIdScope,
                requestHash,
                Instant.parse("2026-05-11T10:00:00Z")
        );
    }
}
