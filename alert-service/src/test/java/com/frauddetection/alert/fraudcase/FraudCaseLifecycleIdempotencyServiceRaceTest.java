package com.frauddetection.alert.fraudcase;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.frauddetection.alert.api.FraudCaseNoteResponse;
import com.frauddetection.alert.idempotency.SharedIdempotencyConflictPolicy;
import com.frauddetection.alert.idempotency.SharedIdempotencyKeyPolicy;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.FraudCaseLifecycleIdempotencyRecordDocument;
import com.frauddetection.alert.persistence.FraudCaseLifecycleIdempotencyRepository;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionSystemException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FraudCaseLifecycleIdempotencyServiceRaceTest {

    private static final String LEGACY_NOTE_JSON = """
            {"id":"note-1","caseId":"case-1","body":"replayed","createdBy":"analyst-1","createdAt":"2026-05-11T10:00:00Z","internalOnly":false}
            """;

    private static final FraudCaseLifecycleIdempotencyCommand COMMAND = new FraudCaseLifecycleIdempotencyCommand(
            "race-key-1",
            "ADD_FRAUD_CASE_NOTE",
            "analyst-1",
            "case-1",
            "request-hash-1",
            Instant.parse("2026-05-11T10:00:00Z")
    );

    @Test
    void duplicateKeyRaceReplaysCompletedRecordWithoutLeakingRawException() {
        AtomicInteger mutationCalls = new AtomicInteger();
        FraudCaseLifecycleIdempotencyService service = duplicateInsertService(existing("request-hash-1", LEGACY_NOTE_JSON));

        FraudCaseNoteResponse response = service.execute(COMMAND, () -> {
            mutationCalls.incrementAndGet();
            return note("mutated");
        }, FraudCaseNoteResponse.class);

        assertThat(response.body()).isEqualTo("replayed");
        assertThat(mutationCalls).hasValue(0);
    }

    @Test
    void duplicateKeyRaceWithoutVisibleRecordBecomesInProgressOutcome() {
        FraudCaseLifecycleIdempotencyService service = duplicateInsertService(null);

        assertThatThrownBy(() -> service.execute(COMMAND, () -> "mutated", String.class))
                .isInstanceOf(FraudCaseIdempotencyInProgressException.class)
                .isNotInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void duplicateKeyRaceRecordsBoundedRaceResolvedMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FraudCaseLifecycleIdempotencyService service = duplicateInsertService(null, new AlertServiceMetrics(registry));

        assertThatThrownBy(() -> service.execute(COMMAND, () -> "mutated", String.class))
                .isInstanceOf(FraudCaseIdempotencyInProgressException.class);

        assertThat(registry.get("fraud_case_lifecycle_idempotency_total")
                .tag("outcome", "race_resolved")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void duplicateKeyRaceWithDifferentClaimBecomesConflictOutcome() {
        FraudCaseLifecycleIdempotencyService service = duplicateInsertService(existing("different-request-hash", "\"replayed\""));

        assertThatThrownBy(() -> service.execute(COMMAND, () -> "mutated", String.class))
                .isInstanceOf(FraudCaseIdempotencyConflictException.class)
                .isNotInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void unknownDataAccessExceptionDuringInitialSavePropagatesWithoutReplayOrInProgressOutcome() {
        AtomicInteger mutationCalls = new AtomicInteger();
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("primary unavailable");
        FraudCaseLifecycleIdempotencyService service = saveFailureService(failure, null);

        assertThatThrownBy(() -> service.execute(COMMAND, () -> {
                    mutationCalls.incrementAndGet();
                    return "mutated";
                }, String.class))
                .isSameAs(failure)
                .isNotInstanceOf(FraudCaseIdempotencyInProgressException.class);
        assertThat(mutationCalls).hasValue(0);
    }

    @Test
    void unknownTransactionSystemExceptionPropagatesWithoutIdempotencyDomainOutcome() {
        TransactionSystemException failure = new TransactionSystemException("commit failed for unrelated reason");
        FraudCaseLifecycleIdempotencyService service = transactionFailureService(failure, existing("request-hash-1", "\"replayed\""));

        assertThatThrownBy(() -> service.execute(COMMAND, () -> "mutated", String.class))
                .isSameAs(failure)
                .isNotInstanceOf(FraudCaseIdempotencyInProgressException.class)
                .isNotInstanceOf(FraudCaseIdempotencyConflictException.class);
    }

    @Test
    void knownMongoWriteConflictTransactionSignalBecomesInProgressWhenNoRecordIsVisible() {
        TransactionSystemException failure = new TransactionSystemException(
                "Could not commit Mongo transaction after WriteConflict with TransientTransactionError"
        );
        FraudCaseLifecycleIdempotencyService service = transactionFailureService(failure, null);

        assertThatThrownBy(() -> service.execute(COMMAND, () -> "mutated", String.class))
                .isInstanceOf(FraudCaseIdempotencyInProgressException.class)
                .isNotInstanceOf(TransactionSystemException.class);
    }

    @Test
    void knownMongoWriteConflictTransactionSignalReplaysCompletedRecordWhenVisible() {
        TransactionSystemException failure = new TransactionSystemException(
                "Could not commit Mongo transaction after WriteConflict with TransientTransactionError"
        );
        FraudCaseLifecycleIdempotencyService service = transactionFailureService(failure, existing("request-hash-1", LEGACY_NOTE_JSON));

        FraudCaseNoteResponse response = service.execute(COMMAND, () -> note("mutated"), FraudCaseNoteResponse.class);

        assertThat(response.body()).isEqualTo("replayed");
    }

    @Test
    void invalidRetentionFailsConstruction() {
        assertThatThrownBy(() -> new FraudCaseLifecycleIdempotencyService(
                mock(FraudCaseLifecycleIdempotencyRepository.class),
                new SharedIdempotencyKeyPolicy(),
                new FraudCaseLifecycleIdempotencyConflictPolicy(new SharedIdempotencyConflictPolicy()),
                transactionRunner(),
                JsonMapper.builder().addModule(new JavaTimeModule()).build(),
                FraudCaseLifecycleIdempotencyService.MAX_RESPONSE_SNAPSHOT_BYTES,
                Duration.ZERO
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Fraud-case lifecycle idempotency retention must be positive.");
    }

    @Test
    void completedAtUsesCompletionClockWhileExpiryRemainsBasedOnCreatedAt() {
        FraudCaseLifecycleIdempotencyRepository repository = mock(FraudCaseLifecycleIdempotencyRepository.class);
        AtomicReference<FraudCaseLifecycleIdempotencyRecordDocument> completedRecord = new AtomicReference<>();
        when(repository.findByIdempotencyKeyHash(any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            FraudCaseLifecycleIdempotencyRecordDocument record = invocation.getArgument(0);
            if (record.getStatus() == FraudCaseLifecycleIdempotencyStatus.COMPLETED) {
                completedRecord.set(record);
            }
            return record;
        });
        Instant completionTime = Instant.parse("2026-05-11T10:00:15Z");
        FraudCaseLifecycleIdempotencyService service = new FraudCaseLifecycleIdempotencyService(
                repository,
                new SharedIdempotencyKeyPolicy(),
                new FraudCaseLifecycleIdempotencyConflictPolicy(new SharedIdempotencyConflictPolicy()),
                transactionRunner(),
                JsonMapper.builder().addModule(new JavaTimeModule()).build(),
                FraudCaseLifecycleIdempotencyService.MAX_RESPONSE_SNAPSHOT_BYTES,
                Duration.ofHours(24),
                null,
                Clock.fixed(completionTime, java.time.ZoneOffset.UTC)
        );

        service.execute(COMMAND, () -> new FraudCaseNoteResponse(
                "note-1",
                "case-1",
                "Completion clock proof note",
                "analyst-1",
                completionTime,
                false
        ), FraudCaseNoteResponse.class);

        assertThat(completedRecord.get().getCreatedAt()).isEqualTo(COMMAND.now());
        assertThat(completedRecord.get().getCompletedAt()).isEqualTo(completionTime);
        assertThat(completedRecord.get().getCompletedAt()).isAfterOrEqualTo(completedRecord.get().getCreatedAt());
        assertThat(completedRecord.get().getExpiresAt()).isEqualTo(COMMAND.now().plus(Duration.ofHours(24)));
    }

    private FraudCaseLifecycleIdempotencyService duplicateInsertService(
            FraudCaseLifecycleIdempotencyRecordDocument existing
    ) {
        return duplicateInsertService(existing, null);
    }

    private FraudCaseLifecycleIdempotencyService duplicateInsertService(
            FraudCaseLifecycleIdempotencyRecordDocument existing,
            AlertServiceMetrics metrics
    ) {
        return new DuplicateInsertFraudCaseLifecycleIdempotencyService(
                mock(FraudCaseLifecycleIdempotencyRepository.class),
                new SharedIdempotencyKeyPolicy(),
                new FraudCaseLifecycleIdempotencyConflictPolicy(new SharedIdempotencyConflictPolicy()),
                transactionRunner(),
                existing,
                metrics
        );
    }

    private FraudCaseLifecycleIdempotencyService saveFailureService(
            RuntimeException failure,
            FraudCaseLifecycleIdempotencyRecordDocument existing
    ) {
        return new SaveFailureFraudCaseLifecycleIdempotencyService(
                mock(FraudCaseLifecycleIdempotencyRepository.class),
                new SharedIdempotencyKeyPolicy(),
                new FraudCaseLifecycleIdempotencyConflictPolicy(new SharedIdempotencyConflictPolicy()),
                transactionRunner(),
                failure,
                existing
        );
    }

    private FraudCaseLifecycleIdempotencyService transactionFailureService(
            RuntimeException failure,
            FraudCaseLifecycleIdempotencyRecordDocument existing
    ) {
        RegulatedMutationTransactionRunner transactionRunner = mock(RegulatedMutationTransactionRunner.class);
        when(transactionRunner.runLocalCommit(any())).thenThrow(failure);
        return new ExistingRecordFraudCaseLifecycleIdempotencyService(
                mock(FraudCaseLifecycleIdempotencyRepository.class),
                new SharedIdempotencyKeyPolicy(),
                new FraudCaseLifecycleIdempotencyConflictPolicy(new SharedIdempotencyConflictPolicy()),
                transactionRunner,
                existing
        );
    }

    private RegulatedMutationTransactionRunner transactionRunner() {
        RegulatedMutationTransactionRunner transactionRunner = mock(RegulatedMutationTransactionRunner.class);
        when(transactionRunner.runLocalCommit(any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
        return transactionRunner;
    }

    private FraudCaseLifecycleIdempotencyRecordDocument existing(String requestHash, String responsePayloadSnapshot) {
        FraudCaseLifecycleIdempotencyRecordDocument document = new FraudCaseLifecycleIdempotencyRecordDocument();
        document.setIdempotencyKeyHash("stored-key-hash");
        document.setAction(COMMAND.action());
        document.setActorId(COMMAND.actorId());
        document.setCaseId(COMMAND.caseIdScope());
        document.setCaseIdScope(COMMAND.caseIdScope());
        document.setRequestHash(requestHash);
        document.setStatus(FraudCaseLifecycleIdempotencyStatus.COMPLETED);
        document.setResponsePayloadSnapshot(responsePayloadSnapshot);
        return document;
    }

    private FraudCaseNoteResponse note(String body) {
        return new FraudCaseNoteResponse(
                "note-1",
                "case-1",
                body,
                "analyst-1",
                Instant.parse("2026-05-11T10:00:00Z"),
                false
        );
    }

    private static final class DuplicateInsertFraudCaseLifecycleIdempotencyService
            extends FraudCaseLifecycleIdempotencyService {

        private final FraudCaseLifecycleIdempotencyRecordDocument existing;

        private DuplicateInsertFraudCaseLifecycleIdempotencyService(
                FraudCaseLifecycleIdempotencyRepository repository,
                SharedIdempotencyKeyPolicy keyPolicy,
                FraudCaseLifecycleIdempotencyConflictPolicy conflictPolicy,
                RegulatedMutationTransactionRunner transactionRunner,
                FraudCaseLifecycleIdempotencyRecordDocument existing,
                AlertServiceMetrics metrics
        ) {
            super(
                    repository,
                    keyPolicy,
                    conflictPolicy,
                    transactionRunner,
                    JsonMapper.builder().addModule(new JavaTimeModule()).build(),
                    MAX_RESPONSE_SNAPSHOT_BYTES,
                    DEFAULT_RETENTION,
                    metrics,
                    Clock.systemUTC()
            );
            this.existing = existing;
        }

        @Override
        protected FraudCaseLifecycleIdempotencyRecordDocument saveRecord(FraudCaseLifecycleIdempotencyRecordDocument record) {
            if (record.getStatus() == FraudCaseLifecycleIdempotencyStatus.IN_PROGRESS) {
                throw new DuplicateKeyException("duplicate key on fraud case lifecycle idempotency record");
            }
            return record;
        }

        @Override
        protected Optional<FraudCaseLifecycleIdempotencyRecordDocument> findRecordByKeyHash(String keyHash) {
            return Optional.ofNullable(existing);
        }
    }

    private static class ExistingRecordFraudCaseLifecycleIdempotencyService extends FraudCaseLifecycleIdempotencyService {

        private final FraudCaseLifecycleIdempotencyRecordDocument existing;

        protected ExistingRecordFraudCaseLifecycleIdempotencyService(
                FraudCaseLifecycleIdempotencyRepository repository,
                SharedIdempotencyKeyPolicy keyPolicy,
                FraudCaseLifecycleIdempotencyConflictPolicy conflictPolicy,
                RegulatedMutationTransactionRunner transactionRunner,
                FraudCaseLifecycleIdempotencyRecordDocument existing
        ) {
            super(
                    repository,
                    keyPolicy,
                    conflictPolicy,
                    transactionRunner,
                    JsonMapper.builder().addModule(new JavaTimeModule()).build(),
                    MAX_RESPONSE_SNAPSHOT_BYTES
            );
            this.existing = existing;
        }

        @Override
        protected Optional<FraudCaseLifecycleIdempotencyRecordDocument> findRecordByKeyHash(String keyHash) {
            return Optional.ofNullable(existing);
        }
    }

    private static final class SaveFailureFraudCaseLifecycleIdempotencyService
            extends ExistingRecordFraudCaseLifecycleIdempotencyService {

        private final RuntimeException failure;

        private SaveFailureFraudCaseLifecycleIdempotencyService(
                FraudCaseLifecycleIdempotencyRepository repository,
                SharedIdempotencyKeyPolicy keyPolicy,
                FraudCaseLifecycleIdempotencyConflictPolicy conflictPolicy,
                RegulatedMutationTransactionRunner transactionRunner,
                RuntimeException failure,
                FraudCaseLifecycleIdempotencyRecordDocument existing
        ) {
            super(repository, keyPolicy, conflictPolicy, transactionRunner, existing);
            this.failure = failure;
        }

        @Override
        protected FraudCaseLifecycleIdempotencyRecordDocument saveRecord(FraudCaseLifecycleIdempotencyRecordDocument record) {
            if (record.getStatus() == FraudCaseLifecycleIdempotencyStatus.IN_PROGRESS) {
                throw failure;
            }
            return record;
        }
    }

}
