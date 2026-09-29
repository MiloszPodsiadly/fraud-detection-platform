package com.frauddetection.alert.regulated;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class MongoRegulatedMutationCoordinator implements RegulatedMutationCoordinator {

    private final RegulatedMutationCommandRepository commandRepository;
    private final RegulatedMutationExecutorRegistry executorRegistry;
    private final RegulatedMutationConflictPolicy conflictPolicy;

    public MongoRegulatedMutationCoordinator(
            RegulatedMutationCommandRepository commandRepository,
            RegulatedMutationExecutorRegistry executorRegistry
    ) {
        this(commandRepository, executorRegistry, new RegulatedMutationConflictPolicy());
    }

    @Autowired
    public MongoRegulatedMutationCoordinator(
            RegulatedMutationCommandRepository commandRepository,
            RegulatedMutationExecutorRegistry executorRegistry,
            RegulatedMutationConflictPolicy conflictPolicy
    ) {
        this.commandRepository = commandRepository;
        this.executorRegistry = executorRegistry;
        this.conflictPolicy = conflictPolicy;
    }

    @Override
    public <R, S> RegulatedMutationResult<S> commit(RegulatedMutationCommand<R, S> command) {
        requireCurrentCommand(command);
        String idempotencyKey = normalize(command.idempotencyKey());
        if (idempotencyKey == null) {
            throw new MissingIdempotencyKeyException();
        }

        RegulatedMutationCommandDocument document = createOrLoad(command, idempotencyKey);
        return executorRegistry.executorFor(document).execute(command, idempotencyKey, document);
    }

    private <R, S> void requireCurrentCommand(RegulatedMutationCommand<R, S> command) {
        if (command == null) {
            throw new IllegalArgumentException("Regulated mutation command is required.");
        }
        if (command.mutationModelVersion() != RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1) {
            throw new IllegalStateException("New regulated mutation commands require explicit model version EVIDENCE_GATED_FINALIZE_V1.");
        }
        RegulatedMutationDefinitions.requireSupported(command.action(), command.resourceType());
    }

    private <R, S> RegulatedMutationCommandDocument createOrLoad(
            RegulatedMutationCommand<R, S> command,
            String idempotencyKey
    ) {
        return commandRepository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> conflictPolicy.existingOrConflict(existing, command))
                .orElseGet(() -> createCommand(command, idempotencyKey));
    }

    private <R, S> RegulatedMutationCommandDocument createCommand(RegulatedMutationCommand<R, S> command, String idempotencyKey) {
        Instant now = Instant.now();
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setIdempotencyKey(idempotencyKey);
        document.setActorId(command.actorId());
        document.setResourceId(command.resourceId());
        document.setResourceType(command.resourceType().name());
        document.setAction(command.action().name());
        document.setCorrelationId(normalize(command.correlationId()));
        document.setRequestHash(command.requestHash());
        document.setIdempotencyKeyHash(RegulatedMutationIntentHasher.hash(idempotencyKey));
        document.setMutationModelVersion(command.mutationModelVersion());
        document.setRevision(0L);
        applyIntent(command, document);
        document.setState(RegulatedMutationState.REQUESTED);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.NEW);
        document.setId(UUID.randomUUID().toString());
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        try {
            return commandRepository.save(document);
        } catch (DuplicateKeyException duplicate) {
            return commandRepository.findByIdempotencyKey(idempotencyKey)
                    .map(existing -> conflictPolicy.existingOrConflict(existing, command))
                    .orElseThrow(() -> duplicate);
        }
    }

    private <R, S> void applyIntent(RegulatedMutationCommand<R, S> command, RegulatedMutationCommandDocument document) {
        RegulatedMutationIntent intent = command.intent();
        if (intent == null) {
            document.setIntentHash(command.requestHash());
            document.setIntentResourceId(command.resourceId());
            document.setIntentAction(command.action().name());
            document.setIntentActorId(command.actorId());
            return;
        }
        document.setIntentHash(intent.intentHash());
        document.setIntentResourceId(intent.resourceId());
        document.setIntentAction(intent.action());
        document.setIntentActorId(intent.actorId());
        document.setIntentDecision(intent.decision());
        document.setIntentReasonHash(intent.reasonHash());
        document.setIntentTagsHash(intent.tagsHash());
        document.setIntentStatus(intent.status());
        document.setIntentAssigneeHash(intent.assigneeHash());
        document.setIntentNotesHash(intent.notesHash());
        document.setIntentPayloadHash(intent.payloadHash());
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

}
