package com.frauddetection.alert.fraudcase;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.frauddetection.alert.idempotency.SharedIdempotencyConflictPolicy;
import com.frauddetection.alert.idempotency.SharedIdempotencyKeyPolicy;
import com.frauddetection.alert.persistence.FraudCaseLifecycleIdempotencyRecordDocument;
import com.frauddetection.alert.persistence.FraudCaseLifecycleIdempotencyRepository;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.Indexed;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Fdp44FraudCaseIdempotencyRetentionOperationalTest {

    @Test
    void retentionMustBePositive() {
        assertInvalidRetention(null);
        assertInvalidRetention(Duration.ZERO);
        assertInvalidRetention(Duration.ofSeconds(-1));
    }

    @Test
    void completedRecordsExpireAtCreatedAtPlusRetention() {
        AtomicReference<FraudCaseLifecycleIdempotencyRecordDocument> stored = new AtomicReference<>();
        FraudCaseLifecycleIdempotencyRepository repository = mock(FraudCaseLifecycleIdempotencyRepository.class);
        when(repository.findByIdempotencyKeyHash(any())).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.save(any())).thenAnswer(invocation -> {
            FraudCaseLifecycleIdempotencyRecordDocument record = invocation.getArgument(0);
            stored.set(record);
            return record;
        });
        RegulatedMutationTransactionRunner transactionRunner = mock(RegulatedMutationTransactionRunner.class);
        when(transactionRunner.runLocalCommit(any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());

        Instant createdAt = Instant.parse("2026-05-11T10:00:00Z");
        Instant completedAt = Instant.parse("2026-05-11T10:00:20Z");
        Duration retention = Duration.ofHours(6);
        FraudCaseLifecycleIdempotencyService service = new FraudCaseLifecycleIdempotencyService(
                repository,
                new SharedIdempotencyKeyPolicy(),
                new FraudCaseLifecycleIdempotencyConflictPolicy(new SharedIdempotencyConflictPolicy()),
                transactionRunner,
                JsonMapper.builder().build(),
                FraudCaseLifecycleIdempotencyService.MAX_RESPONSE_SNAPSHOT_BYTES,
                retention,
                null,
                Clock.fixed(completedAt, ZoneOffset.UTC)
        );

        service.execute(
                new FraudCaseLifecycleIdempotencyCommand(
                        "retention-key",
                        "ADD_FRAUD_CASE_NOTE",
                        "analyst-1",
                        "case-1",
                        "request-hash-1",
                        createdAt
                ),
                () -> "ok",
                String.class
        );

        FraudCaseLifecycleIdempotencyRecordDocument completed = stored.get();
        assertThat(completed.getStatus()).isEqualTo(FraudCaseLifecycleIdempotencyStatus.COMPLETED);
        assertThat(completed.getCreatedAt()).isEqualTo(createdAt);
        assertThat(completed.getCompletedAt()).isEqualTo(completedAt);
        assertThat(completed.getExpiresAt()).isEqualTo(createdAt.plus(retention));
    }

    @Test
    void expiresAtUsesMongoTtlIndex() throws Exception {
        Indexed indexed = FraudCaseLifecycleIdempotencyRecordDocument.class
                .getDeclaredField("expiresAt")
                .getAnnotation(Indexed.class);

        assertThat(indexed).isNotNull();
        assertThat(indexed.expireAfterSeconds()).isZero();
    }

    private void assertInvalidRetention(Duration retention) {
        assertThatThrownBy(() -> new FraudCaseLifecycleIdempotencyService(
                mock(FraudCaseLifecycleIdempotencyRepository.class),
                new SharedIdempotencyKeyPolicy(),
                new FraudCaseLifecycleIdempotencyConflictPolicy(new SharedIdempotencyConflictPolicy()),
                mock(RegulatedMutationTransactionRunner.class),
                JsonMapper.builder().build(),
                FraudCaseLifecycleIdempotencyService.MAX_RESPONSE_SNAPSHOT_BYTES,
                retention
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Fraud-case lifecycle idempotency retention must be positive.");
    }
}
