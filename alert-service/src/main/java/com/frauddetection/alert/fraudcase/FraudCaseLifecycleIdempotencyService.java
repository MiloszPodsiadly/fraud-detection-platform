package com.frauddetection.alert.fraudcase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.alert.idempotency.SharedIdempotencyKeyPolicy;
import com.frauddetection.alert.idempotency.SharedInvalidIdempotencyKeyException;
import com.frauddetection.alert.idempotency.SharedMissingIdempotencyKeyException;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.FraudCaseLifecycleIdempotencyRecordDocument;
import com.frauddetection.alert.persistence.FraudCaseLifecycleIdempotencyRepository;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

@Service
public class FraudCaseLifecycleIdempotencyService {

    public static final int MAX_RESPONSE_SNAPSHOT_BYTES = 64 * 1024;
    static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

    private final FraudCaseLifecycleIdempotencyRepository repository;
    private final SharedIdempotencyKeyPolicy keyPolicy;
    private final FraudCaseLifecycleIdempotencyConflictPolicy conflictPolicy;
    private final RegulatedMutationTransactionRunner transactionRunner;
    private final ObjectMapper objectMapper;
    private final int maxResponseSnapshotBytes;
    private final Duration retention;
    private final AlertServiceMetrics metrics;
    private final Clock clock;

    @Autowired
    public FraudCaseLifecycleIdempotencyService(
            FraudCaseLifecycleIdempotencyRepository repository,
            SharedIdempotencyKeyPolicy keyPolicy,
            FraudCaseLifecycleIdempotencyConflictPolicy conflictPolicy,
            RegulatedMutationTransactionRunner transactionRunner,
            ObjectMapper objectMapper,
            AlertServiceMetrics metrics,
            @Value("${app.fraud-cases.idempotency.retention:PT24H}") Duration retention
    ) {
        this(repository, keyPolicy, conflictPolicy, transactionRunner, objectMapper, MAX_RESPONSE_SNAPSHOT_BYTES, retention, metrics, Clock.systemUTC());
    }

    FraudCaseLifecycleIdempotencyService(
            FraudCaseLifecycleIdempotencyRepository repository,
            SharedIdempotencyKeyPolicy keyPolicy,
            FraudCaseLifecycleIdempotencyConflictPolicy conflictPolicy,
            RegulatedMutationTransactionRunner transactionRunner,
            ObjectMapper objectMapper
    ) {
        this(repository, keyPolicy, conflictPolicy, transactionRunner, objectMapper, MAX_RESPONSE_SNAPSHOT_BYTES, DEFAULT_RETENTION, null, Clock.systemUTC());
    }

    FraudCaseLifecycleIdempotencyService(
            FraudCaseLifecycleIdempotencyRepository repository,
            SharedIdempotencyKeyPolicy keyPolicy,
            FraudCaseLifecycleIdempotencyConflictPolicy conflictPolicy,
            RegulatedMutationTransactionRunner transactionRunner,
            ObjectMapper objectMapper,
            int maxResponseSnapshotBytes
    ) {
        this(repository, keyPolicy, conflictPolicy, transactionRunner, objectMapper, maxResponseSnapshotBytes, DEFAULT_RETENTION, null, Clock.systemUTC());
    }

    FraudCaseLifecycleIdempotencyService(
            FraudCaseLifecycleIdempotencyRepository repository,
            SharedIdempotencyKeyPolicy keyPolicy,
            FraudCaseLifecycleIdempotencyConflictPolicy conflictPolicy,
            RegulatedMutationTransactionRunner transactionRunner,
            ObjectMapper objectMapper,
            int maxResponseSnapshotBytes,
            Duration retention
    ) {
        this(repository, keyPolicy, conflictPolicy, transactionRunner, objectMapper, maxResponseSnapshotBytes, retention, null, Clock.systemUTC());
    }

    FraudCaseLifecycleIdempotencyService(
            FraudCaseLifecycleIdempotencyRepository repository,
            SharedIdempotencyKeyPolicy keyPolicy,
            FraudCaseLifecycleIdempotencyConflictPolicy conflictPolicy,
            RegulatedMutationTransactionRunner transactionRunner,
            ObjectMapper objectMapper,
            int maxResponseSnapshotBytes,
            Duration retention,
            AlertServiceMetrics metrics,
            Clock clock
    ) {
        this.repository = repository;
        this.keyPolicy = keyPolicy;
        this.conflictPolicy = conflictPolicy;
        this.transactionRunner = transactionRunner;
        this.objectMapper = objectMapper;
        this.maxResponseSnapshotBytes = maxResponseSnapshotBytes;
        this.retention = validateRetention(retention);
        this.metrics = metrics;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public <T> T execute(FraudCaseLifecycleIdempotencyCommand command, Supplier<T> mutation, Class<T> responseType) {
        String normalizedKey = normalize(command.idempotencyKey());
        String keyHash = keyPolicy.hashKey(normalizedKey);
        FraudCaseLifecycleIdempotencyCommand normalizedCommand = new FraudCaseLifecycleIdempotencyCommand(
                normalizedKey,
                command.action(),
                command.actorId(),
                command.caseIdScope(),
                command.requestHash(),
                command.now()
        );
        try {
            return transactionRunner.runLocalCommit(() -> executeInTransaction(keyHash, normalizedCommand, mutation, responseType));
        } catch (DataAccessException exception) {
            if (!isIdempotencyRaceSignal(exception)) {
                throw exception;
            }
            return resolveIdempotencyRace(keyHash, normalizedCommand, responseType);
        } catch (TransactionSystemException exception) {
            if (!isIdempotencyRaceSignal(exception)) {
                throw exception;
            }
            return resolveIdempotencyRace(keyHash, normalizedCommand, responseType);
        }
    }

    private <T> T executeInTransaction(
            String keyHash,
            FraudCaseLifecycleIdempotencyCommand command,
            Supplier<T> mutation,
            Class<T> responseType
    ) {
        return findRecordByKeyHash(keyHash)
                .map(existing -> existingResponseOrConflict(existing, command, responseType, false))
                .orElseGet(() -> createAndExecute(keyHash, command, mutation, responseType));
    }

    private <T> T createAndExecute(
            String keyHash,
            FraudCaseLifecycleIdempotencyCommand command,
            Supplier<T> mutation,
            Class<T> responseType
    ) {
        FraudCaseLifecycleIdempotencyRecordDocument record = new FraudCaseLifecycleIdempotencyRecordDocument();
        record.setIdempotencyKeyHash(keyHash);
        record.setAction(command.action());
        record.setActorId(command.actorId());
        record.setCaseId("CREATE".equals(command.caseIdScope()) ? null : command.caseIdScope());
        record.setCaseIdScope(command.caseIdScope());
        record.setRequestHash(command.requestHash());
        record.setStatus(FraudCaseLifecycleIdempotencyStatus.IN_PROGRESS);
        record.setCreatedAt(command.now());
        record.setExpiresAt(command.now().plus(retention));
        try {
            saveRecord(record);
        } catch (DataAccessException exception) {
            if (!isIdempotencyRaceSignal(exception)) {
                recordOutcome("failure");
                throw exception;
            }
            return resolveIdempotencyRace(keyHash, command, responseType);
        }
        T response = mutation.get();
        try {
            record.setResponsePayloadSnapshot(snapshot(response));
        } catch (FraudCaseIdempotencySnapshotTooLargeException exception) {
            recordOutcome("snapshot_too_large");
            throw exception;
        } catch (RuntimeException exception) {
            recordOutcome("failure");
            throw exception;
        }
        record.setStatus(FraudCaseLifecycleIdempotencyStatus.COMPLETED);
        record.setCompletedAt(Instant.now(clock));
        try {
            saveRecord(record);
        } catch (DataAccessException exception) {
            recordOutcome("failure");
            throw exception;
        }
        recordOutcome("new");
        return response;
    }

    private <T> T resolveIdempotencyRace(
            String keyHash,
            FraudCaseLifecycleIdempotencyCommand command,
            Class<T> responseType
    ) {
        recordOutcome("race_resolved");
        FraudCaseLifecycleIdempotencyRecordDocument existing = findRecordByKeyHash(keyHash)
                .orElseThrow(FraudCaseIdempotencyInProgressException::new);
        return existingResponseOrConflict(existing, command, responseType, true);
    }

    private boolean isIdempotencyRaceSignal(Throwable exception) {
        return exception instanceof DuplicateKeyException
                || containsDuplicateKeySignal(exception)
                || containsWriteConflictSignal(exception);
    }

    private boolean containsDuplicateKeySignal(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String className = current.getClass().getName();
            String message = current.getMessage();
            if (className.contains("DuplicateKey")
                    || (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("duplicate key"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean containsWriteConflictSignal(Throwable throwable) {
        // Narrow Mongo same-key race translation only. Unknown DataAccessException or
        // TransactionSystemException must propagate so business, audit, or mutation failures
        // are never misreported as idempotency replay/in-progress outcomes.
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("write conflict")
                        || normalized.contains("transienttransactionerror")
                        || normalized.contains("could not commit mongo transaction")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private <T> T existingResponseOrConflict(
            FraudCaseLifecycleIdempotencyRecordDocument existing,
            FraudCaseLifecycleIdempotencyCommand command,
            Class<T> responseType,
            boolean raceResolved
    ) {
        try {
            conflictPolicy.validateSameOperation(existing, command);
        } catch (FraudCaseIdempotencyConflictException exception) {
            if (!raceResolved) {
                recordOutcome("conflict");
            }
            throw exception;
        }
        if (existing.getStatus() == FraudCaseLifecycleIdempotencyStatus.COMPLETED
                && StringUtils.hasText(existing.getResponsePayloadSnapshot())) {
            if (!raceResolved) {
                recordOutcome("replay");
            }
            return restore(existing.getResponsePayloadSnapshot(), responseType);
        }
        if (!raceResolved) {
            recordOutcome("in_progress");
        }
        throw new FraudCaseIdempotencyInProgressException();
    }

    private String normalize(String rawKey) {
        try {
            return keyPolicy.normalizeRequired(rawKey);
        } catch (SharedMissingIdempotencyKeyException exception) {
            throw new FraudCaseMissingIdempotencyKeyException();
        } catch (SharedInvalidIdempotencyKeyException exception) {
            throw new FraudCaseInvalidIdempotencyKeyException();
        }
    }

    private String snapshot(Object response) {
        try {
            String serialized = objectMapper.writeValueAsString(response);
            if (serialized.getBytes(StandardCharsets.UTF_8).length > maxResponseSnapshotBytes) {
                throw new FraudCaseIdempotencySnapshotTooLargeException();
            }
            return serialized;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Fraud case lifecycle idempotency response snapshot failed.", exception);
        }
    }

    private <T> T restore(String snapshot, Class<T> responseType) {
        try {
            return objectMapper.readValue(snapshot, responseType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Fraud case lifecycle idempotency response replay failed.", exception);
        }
    }

    protected FraudCaseLifecycleIdempotencyRecordDocument saveRecord(FraudCaseLifecycleIdempotencyRecordDocument record) {
        return repository.save(record);
    }

    protected java.util.Optional<FraudCaseLifecycleIdempotencyRecordDocument> findRecordByKeyHash(String keyHash) {
        // FDP-43 idempotency keys are globally unique within the fraud-case lifecycle idempotency domain.
        // Do not scope lookup by action, actor, or case.
        return repository.findByIdempotencyKeyHash(keyHash);
    }

    private void recordOutcome(String outcome) {
        if (metrics != null) {
            metrics.recordFraudCaseLifecycleIdempotencyOutcome(outcome);
        }
    }

    private Duration validateRetention(Duration retention) {
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("Fraud-case lifecycle idempotency retention must be positive.");
        }
        return retention;
    }
}
