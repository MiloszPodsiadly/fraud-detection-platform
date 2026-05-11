package com.frauddetection.alert.fraudcase;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.frauddetection.alert.api.FraudCaseDecisionResponse;
import com.frauddetection.alert.api.FraudCaseNoteResponse;
import com.frauddetection.alert.idempotency.SharedIdempotencyConflictPolicy;
import com.frauddetection.alert.idempotency.SharedIdempotencyKeyPolicy;
import com.frauddetection.alert.persistence.FraudCaseLifecycleIdempotencyRecordDocument;
import com.frauddetection.alert.persistence.FraudCaseLifecycleIdempotencyRepository;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import org.junit.jupiter.api.Test;

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

class Fdp44FraudCaseLifecycleReplaySnapshotFailClosedTest {

    @Test
    void unsupportedResponseTypeFailsClosedWithoutRawSnapshotWrite() {
        AtomicReference<FraudCaseLifecycleIdempotencyRecordDocument> stored = new AtomicReference<>();
        FraudCaseLifecycleIdempotencyService service = service(stored);
        FraudCaseLifecycleIdempotencyCommand command = command();
        AtomicInteger mutationCalls = new AtomicInteger();

        assertThatThrownBy(() -> service.execute(command, () -> {
            mutationCalls.incrementAndGet();
            return new UnsupportedLifecycleResponse("raw-value-that-must-not-be-snapshotted");
        }, UnsupportedLifecycleResponse.class))
                .isInstanceOf(UnsupportedFraudCaseLifecycleReplaySnapshotException.class)
                .hasMessage("Unsupported fraud case lifecycle replay snapshot response type.");

        assertThat(mutationCalls).hasValue(1);
        assertThat(stored.get()).isNotNull();
        assertThat(stored.get().getStatus()).isNotEqualTo(FraudCaseLifecycleIdempotencyStatus.COMPLETED);
        assertThat(stored.get().getResponsePayloadSnapshot()).isNull();

        assertThatThrownBy(() -> service.execute(
                command,
                () -> new UnsupportedLifecycleResponse("second-raw-value"),
                UnsupportedLifecycleResponse.class
        )).isInstanceOf(FraudCaseIdempotencyInProgressException.class);
    }

    @Test
    void mapperRejectsUnsupportedResponseTypeWithoutNullFallback() {
        FraudCaseLifecycleReplaySnapshotMapper mapper = new FraudCaseLifecycleReplaySnapshotMapper();

        assertThatThrownBy(() -> mapper.toSnapshot(
                command(),
                new UnsupportedLifecycleResponse("raw-value-that-must-not-be-snapshotted"),
                Instant.parse("2026-05-11T10:00:15Z")
        )).isInstanceOf(UnsupportedFraudCaseLifecycleReplaySnapshotException.class);
    }

    @Test
    void malformedExplicitSnapshotFailsClosedWithoutLegacyFallback() {
        AtomicReference<FraudCaseLifecycleIdempotencyRecordDocument> stored = completedRecord("""
                {"snapshotFormat":"FDP_44_REPLAY_SNAPSHOT","snapshotVersion":1,"snapshotType":"NOTE","action":"ADD_FRAUD_CASE_NOTE"}
                """);
        FraudCaseLifecycleIdempotencyService service = service(stored);

        assertThatThrownBy(() -> service.execute(command(), () -> legacyNote("mutated"), FraudCaseNoteResponse.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("replay");
    }

    @Test
    void unsupportedExplicitSnapshotVersionFailsClosed() {
        AtomicReference<FraudCaseLifecycleIdempotencyRecordDocument> stored = completedRecord("""
                {"snapshotFormat":"FDP_44_REPLAY_SNAPSHOT","snapshotVersion":2,"snapshotType":"NOTE","action":"ADD_FRAUD_CASE_NOTE","noteResponse":{"id":"note-1","caseId":"case-1","body":"legacy","createdBy":"analyst-1","createdAt":"2026-05-11T10:00:00Z","internalOnly":false}}
                """);
        FraudCaseLifecycleIdempotencyService service = service(stored);

        assertThatThrownBy(() -> service.execute(command(), () -> legacyNote("mutated"), FraudCaseNoteResponse.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("replay");
    }

    @Test
    void explicitSnapshotTypeMismatchFailsClosed() {
        AtomicReference<FraudCaseLifecycleIdempotencyRecordDocument> stored = completedRecord("""
                {"snapshotFormat":"FDP_44_REPLAY_SNAPSHOT","snapshotVersion":1,"snapshotType":"NOTE","action":"ADD_FRAUD_CASE_NOTE","noteResponse":{"id":"note-1","caseId":"case-1","body":"legacy","createdBy":"analyst-1","createdAt":"2026-05-11T10:00:00Z","internalOnly":false}}
                """);
        FraudCaseLifecycleIdempotencyService service = service(stored);

        assertThatThrownBy(() -> service.execute(command(), () -> null, FraudCaseDecisionResponse.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("replay");
    }

    @Test
    void trueLegacyRawSnapshotStillRestoresWhenShapeIsKnown() {
        AtomicReference<FraudCaseLifecycleIdempotencyRecordDocument> stored = completedRecord("""
                {"id":"note-1","caseId":"case-1","body":"legacy","createdBy":"analyst-1","createdAt":"2026-05-11T10:00:00Z","internalOnly":false}
                """);
        FraudCaseLifecycleIdempotencyService service = service(stored);

        FraudCaseNoteResponse replay = service.execute(command(), () -> legacyNote("mutated"), FraudCaseNoteResponse.class);

        assertThat(replay).isEqualTo(legacyNote("legacy"));
    }

    @Test
    void randomCorruptedSnapshotFailsClosed() {
        AtomicReference<FraudCaseLifecycleIdempotencyRecordDocument> stored = completedRecord("not-json");
        FraudCaseLifecycleIdempotencyService service = service(stored);

        assertThatThrownBy(() -> service.execute(command(), () -> legacyNote("mutated"), FraudCaseNoteResponse.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("replay");
    }

    private FraudCaseLifecycleIdempotencyService service(
            AtomicReference<FraudCaseLifecycleIdempotencyRecordDocument> stored
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
                FraudCaseLifecycleIdempotencyService.MAX_RESPONSE_SNAPSHOT_BYTES,
                Duration.ofHours(24)
        );
    }

    private RegulatedMutationTransactionRunner transactionRunner() {
        RegulatedMutationTransactionRunner transactionRunner = mock(RegulatedMutationTransactionRunner.class);
        when(transactionRunner.runLocalCommit(any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
        return transactionRunner;
    }

    private FraudCaseLifecycleIdempotencyCommand command() {
        return new FraudCaseLifecycleIdempotencyCommand(
                "unsupported-response-key",
                "ADD_FRAUD_CASE_NOTE",
                "analyst-1",
                "case-1",
                "request-hash-1",
                Instant.parse("2026-05-11T10:00:00Z")
        );
    }

    private AtomicReference<FraudCaseLifecycleIdempotencyRecordDocument> completedRecord(String snapshot) {
        FraudCaseLifecycleIdempotencyRecordDocument record = new FraudCaseLifecycleIdempotencyRecordDocument();
        record.setIdempotencyKeyHash(new SharedIdempotencyKeyPolicy().hashKey("unsupported-response-key"));
        record.setAction("ADD_FRAUD_CASE_NOTE");
        record.setActorId("analyst-1");
        record.setCaseId("case-1");
        record.setCaseIdScope("case-1");
        record.setRequestHash("request-hash-1");
        record.setStatus(FraudCaseLifecycleIdempotencyStatus.COMPLETED);
        record.setCreatedAt(Instant.parse("2026-05-11T10:00:00Z"));
        record.setCompletedAt(Instant.parse("2026-05-11T10:00:15Z"));
        record.setResponsePayloadSnapshot(snapshot);
        return new AtomicReference<>(record);
    }

    private FraudCaseNoteResponse legacyNote(String body) {
        return new FraudCaseNoteResponse(
                "note-1",
                "case-1",
                body,
                "analyst-1",
                Instant.parse("2026-05-11T10:00:00Z"),
                false
        );
    }

    private record UnsupportedLifecycleResponse(String value) {
    }
}
