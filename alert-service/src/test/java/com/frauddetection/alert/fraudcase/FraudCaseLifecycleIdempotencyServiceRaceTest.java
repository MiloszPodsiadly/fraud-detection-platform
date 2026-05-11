package com.frauddetection.alert.fraudcase;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.frauddetection.alert.idempotency.SharedIdempotencyConflictPolicy;
import com.frauddetection.alert.idempotency.SharedIdempotencyKeyPolicy;
import com.frauddetection.alert.persistence.FraudCaseLifecycleIdempotencyRecordDocument;
import com.frauddetection.alert.persistence.FraudCaseLifecycleIdempotencyRepository;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionSystemException;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FraudCaseLifecycleIdempotencyServiceRaceTest {

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
        FraudCaseLifecycleIdempotencyService service = duplicateInsertService(existing("request-hash-1", "\"replayed\""));

        String response = service.execute(COMMAND, () -> {
            mutationCalls.incrementAndGet();
            return "mutated";
        }, String.class);

        assertThat(response).isEqualTo("replayed");
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
        FraudCaseLifecycleIdempotencyService service = transactionFailureService(failure, existing("request-hash-1", "\"replayed\""));

        String response = service.execute(COMMAND, () -> "mutated", String.class);

        assertThat(response).isEqualTo("replayed");
    }

    private FraudCaseLifecycleIdempotencyService duplicateInsertService(
            FraudCaseLifecycleIdempotencyRecordDocument existing
    ) {
        return new DuplicateInsertFraudCaseLifecycleIdempotencyService(
                mock(FraudCaseLifecycleIdempotencyRepository.class),
                new SharedIdempotencyKeyPolicy(),
                new FraudCaseLifecycleIdempotencyConflictPolicy(new SharedIdempotencyConflictPolicy()),
                transactionRunner(),
                existing
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

    private static final class DuplicateInsertFraudCaseLifecycleIdempotencyService
            extends FraudCaseLifecycleIdempotencyService {

        private final FraudCaseLifecycleIdempotencyRecordDocument existing;

        private DuplicateInsertFraudCaseLifecycleIdempotencyService(
                FraudCaseLifecycleIdempotencyRepository repository,
                SharedIdempotencyKeyPolicy keyPolicy,
                FraudCaseLifecycleIdempotencyConflictPolicy conflictPolicy,
                RegulatedMutationTransactionRunner transactionRunner,
                FraudCaseLifecycleIdempotencyRecordDocument existing
        ) {
            super(repository, keyPolicy, conflictPolicy, transactionRunner, JsonMapper.builder().build(), MAX_RESPONSE_SNAPSHOT_BYTES);
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
        protected Optional<FraudCaseLifecycleIdempotencyRecordDocument> findRecord(
                String keyHash,
                String action,
                String actorId,
                String caseIdScope
        ) {
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
            super(repository, keyPolicy, conflictPolicy, transactionRunner, JsonMapper.builder().build(), MAX_RESPONSE_SNAPSHOT_BYTES);
            this.existing = existing;
        }

        @Override
        protected Optional<FraudCaseLifecycleIdempotencyRecordDocument> findRecord(
                String keyHash,
                String action,
                String actorId,
                String caseIdScope
        ) {
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
