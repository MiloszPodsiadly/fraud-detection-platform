package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.ConflictingIdempotencyKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class MongoRegulatedMutationCoordinator implements RegulatedMutationCoordinator {

    private final RegulatedMutationCommandRepository commandRepository;
    private final RegulatedMutationExecutorRegistry executorRegistry;

    /**
     * Compatibility constructor for unit tests and older focused tests.
     * Production Spring wiring uses the constructor that accepts RegulatedMutationExecutorRegistry.
     */
    public MongoRegulatedMutationCoordinator(
            RegulatedMutationCommandRepository commandRepository,
            MongoTemplate mongoTemplate,
            RegulatedMutationAuditPhaseService auditPhaseService,
            AuditDegradationService auditDegradationService,
            AlertServiceMetrics metrics,
            boolean bankModeFailClosed,
            Duration leaseDuration
    ) {
        this(
                commandRepository,
                legacyOnlyRegistryForCompatibility(
                        commandRepository,
                        mongoTemplate,
                        auditPhaseService,
                        auditDegradationService,
                        metrics,
                        new RegulatedMutationTransactionRunner(RegulatedMutationTransactionMode.OFF, null),
                        new RegulatedMutationPublicStatusMapper(),
                        bankModeFailClosed,
                        leaseDuration
                )
        );
    }

    /**
     * Compatibility constructor for unit tests that need a custom transaction runner.
     * Production Spring wiring uses the fail-closed RegulatedMutationExecutorRegistry bean.
     */
    public MongoRegulatedMutationCoordinator(
            RegulatedMutationCommandRepository commandRepository,
            MongoTemplate mongoTemplate,
            RegulatedMutationAuditPhaseService auditPhaseService,
            AuditDegradationService auditDegradationService,
            AlertServiceMetrics metrics,
            RegulatedMutationTransactionRunner transactionRunner,
            boolean bankModeFailClosed,
            Duration leaseDuration
    ) {
        this(
                commandRepository,
                legacyOnlyRegistryForCompatibility(
                        commandRepository,
                        mongoTemplate,
                        auditPhaseService,
                        auditDegradationService,
                        metrics,
                        transactionRunner,
                        new RegulatedMutationPublicStatusMapper(),
                        bankModeFailClosed,
                        leaseDuration
                )
        );
    }

    /**
     * Production constructor. Spring runtime depends on the validated RegulatedMutationExecutorRegistry bean.
     */
    @Autowired
    public MongoRegulatedMutationCoordinator(
            RegulatedMutationCommandRepository commandRepository,
            RegulatedMutationExecutorRegistry executorRegistry
    ) {
        this.commandRepository = commandRepository;
        this.executorRegistry = executorRegistry;
    }

    /**
     * Compatibility constructor for tests that provide an explicit FDP-29 executor.
     * This constructor is not a production startup guard and must not replace registry bean validation.
     */
    public MongoRegulatedMutationCoordinator(
            RegulatedMutationCommandRepository commandRepository,
            MongoTemplate mongoTemplate,
            RegulatedMutationAuditPhaseService auditPhaseService,
            AuditDegradationService auditDegradationService,
            AlertServiceMetrics metrics,
            RegulatedMutationTransactionRunner transactionRunner,
            RegulatedMutationPublicStatusMapper publicStatusMapper,
            EvidenceGatedFinalizeExecutor evidenceGatedFinalizeExecutor,
            boolean bankModeFailClosed,
            Duration leaseDuration
    ) {
        this(
                commandRepository,
                registryForCompatibility(
                        new LegacyRegulatedMutationExecutor(
                                commandRepository,
                                mongoTemplate,
                                auditPhaseService,
                                auditDegradationService,
                                metrics,
                                transactionRunner,
                                publicStatusMapper,
                                bankModeFailClosed,
                                leaseDuration
                        ),
                        evidenceGatedFinalizeExecutor
                )
        );
    }

    @Override
    public <R, S> RegulatedMutationResult<S> commit(RegulatedMutationCommand<R, S> command) {
        String idempotencyKey = normalize(command.idempotencyKey());
        if (idempotencyKey == null) {
            throw new MissingIdempotencyKeyException();
        }

        RegulatedMutationCommandDocument document = createOrLoad(command, idempotencyKey);
        return executorRegistry.executorFor(document).execute(command, idempotencyKey, document);
    }

    private <R, S> RegulatedMutationCommandDocument createOrLoad(
            RegulatedMutationCommand<R, S> command,
            String idempotencyKey
    ) {
        return commandRepository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> existingOrConflict(existing, command))
                .orElseGet(() -> createCommand(command, idempotencyKey));
    }

    private <R, S> RegulatedMutationCommandDocument existingOrConflict(
            RegulatedMutationCommandDocument existing,
            RegulatedMutationCommand<R, S> command
    ) {
        if (!existing.getRequestHash().equals(command.requestHash())
                || (existing.getIntentActorId() != null && !existing.getIntentActorId().equals(command.actorId()))) {
            throw new ConflictingIdempotencyKeyException();
        }
        return existing;
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
                    .map(existing -> existingOrConflict(existing, command))
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

    private static RegulatedMutationExecutorRegistry legacyOnlyRegistryForCompatibility(
            RegulatedMutationCommandRepository commandRepository,
            MongoTemplate mongoTemplate,
            RegulatedMutationAuditPhaseService auditPhaseService,
            AuditDegradationService auditDegradationService,
            AlertServiceMetrics metrics,
            RegulatedMutationTransactionRunner transactionRunner,
            RegulatedMutationPublicStatusMapper publicStatusMapper,
            boolean bankModeFailClosed,
            Duration leaseDuration
    ) {
        return new RegulatedMutationExecutorRegistry(
                List.of(new LegacyRegulatedMutationExecutor(
                        commandRepository,
                        mongoTemplate,
                        auditPhaseService,
                        auditDegradationService,
                        metrics,
                        transactionRunner,
                        publicStatusMapper,
                        bankModeFailClosed,
                        leaseDuration
                )),
                false
        );
    }

    private static RegulatedMutationExecutorRegistry registryForCompatibility(
            LegacyRegulatedMutationExecutor legacyExecutor,
            EvidenceGatedFinalizeExecutor evidenceGatedFinalizeExecutor
    ) {
        return new RegulatedMutationExecutorRegistry(List.of(legacyExecutor, evidenceGatedFinalizeExecutor), false);
    }
}
