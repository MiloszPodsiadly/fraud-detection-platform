package com.frauddetection.alert.fraudcase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.alert.idempotency.SharedIdempotencyKeyPolicy;
import com.frauddetection.alert.idempotency.SharedInvalidIdempotencyKeyException;
import com.frauddetection.alert.idempotency.SharedMissingIdempotencyKeyException;
import com.frauddetection.alert.persistence.FraudCaseLifecycleIdempotencyRecordDocument;
import com.frauddetection.alert.persistence.FraudCaseLifecycleIdempotencyRepository;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

@Service
public class FraudCaseLifecycleIdempotencyService {

    private final FraudCaseLifecycleIdempotencyRepository repository;
    private final SharedIdempotencyKeyPolicy keyPolicy;
    private final FraudCaseLifecycleIdempotencyConflictPolicy conflictPolicy;
    private final RegulatedMutationTransactionRunner transactionRunner;
    private final ObjectMapper objectMapper;

    public FraudCaseLifecycleIdempotencyService(
            FraudCaseLifecycleIdempotencyRepository repository,
            SharedIdempotencyKeyPolicy keyPolicy,
            FraudCaseLifecycleIdempotencyConflictPolicy conflictPolicy,
            RegulatedMutationTransactionRunner transactionRunner,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.keyPolicy = keyPolicy;
        this.conflictPolicy = conflictPolicy;
        this.transactionRunner = transactionRunner;
        this.objectMapper = objectMapper;
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
        return transactionRunner.runLocalCommit(() -> executeInTransaction(keyHash, normalizedCommand, mutation, responseType));
    }

    private <T> T executeInTransaction(
            String keyHash,
            FraudCaseLifecycleIdempotencyCommand command,
            Supplier<T> mutation,
            Class<T> responseType
    ) {
        return repository.findByIdempotencyKeyHashAndActionAndActorIdAndCaseIdScope(
                        keyHash,
                        command.action(),
                        command.actorId(),
                        command.caseIdScope()
                )
                .map(existing -> existingResponseOrConflict(existing, command, responseType))
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
        try {
            repository.save(record);
        } catch (DuplicateKeyException exception) {
            FraudCaseLifecycleIdempotencyRecordDocument existing = repository.findByIdempotencyKeyHashAndActionAndActorIdAndCaseIdScope(
                            keyHash,
                            command.action(),
                            command.actorId(),
                            command.caseIdScope()
                    )
                    .orElseThrow(FraudCaseIdempotencyInProgressException::new);
            return existingResponseOrConflict(existing, command, responseType);
        }
        T response = mutation.get();
        record.setResponsePayloadSnapshot(snapshot(response));
        record.setResponseStatus("COMPLETED");
        record.setStatus(FraudCaseLifecycleIdempotencyStatus.COMPLETED);
        record.setCompletedAt(command.now());
        repository.save(record);
        return response;
    }

    private <T> T existingResponseOrConflict(
            FraudCaseLifecycleIdempotencyRecordDocument existing,
            FraudCaseLifecycleIdempotencyCommand command,
            Class<T> responseType
    ) {
        conflictPolicy.validateSameOperation(existing, command);
        if (existing.getStatus() == FraudCaseLifecycleIdempotencyStatus.COMPLETED
                && StringUtils.hasText(existing.getResponsePayloadSnapshot())) {
            return restore(existing.getResponsePayloadSnapshot(), responseType);
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
            return objectMapper.writeValueAsString(response);
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
}
