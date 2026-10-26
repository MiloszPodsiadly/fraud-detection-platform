package com.frauddetection.alert.audit;

import com.frauddetection.alert.regulated.RegulatedMutationCommandDocument;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class RegulatedMutationLocalAuditPhaseWriter {

    private final AuditEventRepository auditEventRepository;
    private final AuditAnchorRepository auditAnchorRepository;
    private final AuditChainLockRepository lockRepository;
    private final AlertServiceMetrics metrics;
    private final LocalAuditPhaseWriterProperties properties;
    private final AuditMongoTransactionRunner transactionRunner;
    private final ThreadLocal<AuditChainLockDocument> activeChainLease = new ThreadLocal<>();

    @Autowired
    public RegulatedMutationLocalAuditPhaseWriter(
            AuditEventRepository auditEventRepository,
            AuditAnchorRepository auditAnchorRepository,
            AuditChainLockRepository lockRepository,
            AlertServiceMetrics metrics,
            LocalAuditPhaseWriterProperties properties,
            AuditMongoTransactionRunner transactionRunner
    ) {
        this.auditEventRepository = auditEventRepository;
        this.auditAnchorRepository = auditAnchorRepository;
        this.lockRepository = lockRepository;
        this.metrics = metrics;
        this.properties = properties == null ? new LocalAuditPhaseWriterProperties() : properties;
        this.transactionRunner = transactionRunner == null ? new AuditMongoTransactionRunner() : transactionRunner;
    }

    public RegulatedMutationLocalAuditPhaseWriter(
            AuditEventRepository auditEventRepository,
            AuditAnchorRepository auditAnchorRepository,
            AuditChainLockRepository lockRepository,
            AlertServiceMetrics metrics,
            LocalAuditPhaseWriterProperties properties
    ) {
        this(auditEventRepository, auditAnchorRepository, lockRepository, metrics, properties, new AuditMongoTransactionRunner());
    }

    public RegulatedMutationLocalAuditPhaseWriter(
            AuditEventRepository auditEventRepository,
            AuditAnchorRepository auditAnchorRepository,
            AuditChainLockRepository lockRepository
    ) {
        this(auditEventRepository, auditAnchorRepository, lockRepository, null, new LocalAuditPhaseWriterProperties(),
                new AuditMongoTransactionRunner());
    }

    public String recordSuccessPhase(
            RegulatedMutationCommandDocument command,
            AuditAction action,
            AuditResourceType resourceType
    ) {
        long startedAt = System.nanoTime();
        String phaseKey = phaseKey(command, "SUCCESS");
        AuditEventDocument existingPhase = auditEventRepository.findByRequestId(phaseKey).orElse(null);
        if (existingPhase != null) {
            requireCanonical(existingPhase);
            requireMatchingRequest(command, action, resourceType, phaseKey, existingPhase);
            requireMatchingAnchor(existingPhase);
            recordAppend("DUPLICATE_PHASE", startedAt);
            return existingPhase.auditId();
        }
        AuditEvent event = new AuditEvent(
                new AuditActor(command.getActorId(), Set.of(), Set.of()),
                action,
                resourceType,
                command.getResourceId(),
                Instant.now(),
                command.getCorrelationId(),
                phaseKey,
                AuditOutcome.SUCCESS,
                AuditFailureCategory.NONE,
                null,
                new AuditEventMetadataSummary(
                        command.getCorrelationId(),
                        phaseKey,
                        "alert-service",
                        "1.0",
                        null,
                        null,
                        null,
                        null,
                        null
                )
        );
        String auditId = UUID.randomUUID().toString();
        String anchorId = UUID.randomUUID().toString();
        if (activeChainLease.get() != null) {
            return appendLocalSuccess(command, event, auditId, anchorId, phaseKey, startedAt);
        }
        return withChainLock(() -> appendLocalSuccess(command, event, auditId, anchorId, phaseKey, startedAt));
    }

    public <T> T withChainLock(Supplier<T> callback) {
        if (activeChainLease.get() != null) {
            return callback.get();
        }
        AuditChainLockDocument lease = acquireChainLock();
        activeChainLease.set(lease);
        try {
            return callback.get();
        } finally {
            activeChainLease.remove();
            AtomicBoolean releaseAttempted = new AtomicBoolean();
            Runnable release = () -> {
                if (!releaseAttempted.compareAndSet(false, true)) return;
                try {
                    transactionRunner.requiresNew(() -> {
                        lockRepository.release(lease);
                        return null;
                    });
                } catch (RuntimeException failure) {
                    recordReleaseFailureSafely();
                }
            };
            transactionRunner.afterCommitOrRollback(release, release);
        }
    }

    private AuditChainLockDocument acquireChainLock() {
        long startedAt = System.nanoTime();
        String lockOwner = UUID.randomUUID().toString();
        for (int attempt = 1; attempt <= properties.getMaxAppendAttempts(); attempt++) {
            try {
                return lockRepository.acquire(AuditEventDocument.PARTITION_KEY, lockOwner);
            } catch (AuditChainConflictException conflict) {
                if (attempt == properties.getMaxAppendAttempts() || retryBudgetExhausted(startedAt)) {
                    recordAppend("CHAIN_CONFLICT_EXHAUSTED", startedAt);
                    throw new AuditPersistenceUnavailableException(
                            "Audit chain lock acquisition retry budget exhausted.",
                            conflict
                    );
                }
                recordAppendAttempt("CHAIN_CONFLICT_RETRY");
                recordRetry("LOCK_CONFLICT");
                backoffBeforeRetry();
            } catch (DataAccessException exception) {
                recordAppend("LOCK_ACQUIRE_FAILED", startedAt);
                throw new AuditPersistenceUnavailableException("Audit chain lock acquisition failed.", exception);
            }
        }
        recordAppend("CHAIN_CONFLICT_EXHAUSTED", startedAt);
        throw new AuditPersistenceUnavailableException("Audit chain lock acquisition retry budget exhausted.", null);
    }

    private String appendLocalSuccess(
            RegulatedMutationCommandDocument command,
            AuditEvent event,
            String auditId,
            String anchorId,
            String phaseKey,
            long startedAt
    ) {
        try {
            return transactionRunner.required(() -> appendLocalSuccessInTransaction(
                    command, event, auditId, anchorId, phaseKey, startedAt
            ));
        } catch (RuntimeException exception) {
            AuditEventDocument reconciled = auditEventRepository.findByRequestId(phaseKey).orElse(null);
            if (reconciled != null && matchesRequest(command, event, reconciled)) {
                requireMatchingAnchor(reconciled);
                transactionRunner.afterCommit(() -> recordAppendSafely("SUCCESS", startedAt));
                return reconciled.auditId();
            }
            throw exception;
        }
    }

    private void recordReleaseFailureSafely() {
        try {
            recordAppendAttempt("LOCK_RELEASE_FAILED");
        } catch (RuntimeException ignored) {
            // Metrics must not turn a committed append into an application failure.
        }
        try {
            recordLockReleaseFailure();
        } catch (RuntimeException ignored) {
            // The lease remains generation-fenced and expires if this counter backend fails.
        }
    }

    private String appendLocalSuccessInTransaction(
            RegulatedMutationCommandDocument command,
            AuditEvent event,
            String auditId,
            String anchorId,
            String phaseKey,
            long startedAt
    ) {
        boolean auditEventInserted = false;
        try {
            AuditChainLockDocument lease = activeChainLease.get();
            if (transactionRunner.transactionActive()) {
                if (lease == null) {
                    throw new AuditPersistenceUnavailableException("Audit chain lease is missing.", null);
                }
                lockRepository.fence(lease);
            }
            AuditEventDocument existingPhaseEvent = auditEventRepository.findByRequestId(phaseKey).orElse(null);
            if (existingPhaseEvent != null) {
                requireCanonical(existingPhaseEvent);
                requireMatchingRequest(command, event.action(), event.resourceType(), phaseKey, existingPhaseEvent);
                requireMatchingAnchor(existingPhaseEvent);
                recordAppend("DUPLICATE_PHASE", startedAt);
                return existingPhaseEvent.auditId();
            }
            AuditEventDocument previous = auditEventRepository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY)
                    .orElse(null);
            requireCanonical(previous);
            String previousHash = previous == null ? null : previous.eventHash();
            long chainPosition = nextChainPosition(previous);
            AuditEventDocument document = auditEventRepository.insert(AuditEventDocument.from(
                    auditId,
                    event,
                    previousHash,
                    chainPosition
            ));
            auditEventInserted = true;
            auditAnchorRepository.insert(AuditAnchorDocument.from(anchorId, document));
            transactionRunner.afterCommit(() -> recordAppendSafely("SUCCESS", startedAt));
            return document.auditId();
        } catch (DuplicateKeyException duplicate) {
            return auditEventRepository.findByRequestId(phaseKey)
                    .map(document -> {
                        requireCanonical(document);
                        requireMatchingRequest(command, event.action(), event.resourceType(), phaseKey, document);
                        requireMatchingAnchor(document);
                        recordAppend("DUPLICATE_PHASE", startedAt);
                        return document.auditId();
                    })
                    .orElseThrow(() -> {
                        recordAppendAttempt("CHAIN_CONFLICT_RETRY");
                        recordRetry("DUPLICATE_KEY");
                        return new AuditChainConflictException("Audit chain append raced.");
                    });
        } catch (DataAccessException exception) {
            recordAppend(auditEventInserted ? "ANCHOR_INSERT_FAILED" : "AUDIT_INSERT_FAILED", startedAt);
            throw new AuditPersistenceUnavailableException("Local success audit persistence failed.", exception);
        }
    }

    private boolean matchesRequest(
            RegulatedMutationCommandDocument command,
            AuditEvent event,
            AuditEventDocument document
    ) {
        if (!AuditEventHasher.matches(document)
                || !java.util.Objects.equals(document.action(), event.action())
                || !java.util.Objects.equals(document.resourceType(), event.resourceType())
                || !java.util.Objects.equals(document.resourceId(), event.resourceId())
                || !java.util.Objects.equals(document.actorId(), command.getActorId())
                || !java.util.Objects.equals(document.correlationId(), command.getCorrelationId())
                || !java.util.Objects.equals(document.requestId(), event.requestId())
                || document.outcome() != AuditOutcome.SUCCESS
                || !java.util.Objects.equals(document.metadataSummary(), event.metadataSummary())) {
            return false;
        }
        return true;
    }

    private void requireMatchingRequest(
            RegulatedMutationCommandDocument command,
            AuditAction action,
            AuditResourceType resourceType,
            String phaseKey,
            AuditEventDocument event
    ) {
        if (!java.util.Objects.equals(event.action(), action)
                || !java.util.Objects.equals(event.resourceType(), resourceType)
                || !java.util.Objects.equals(event.resourceId(), command.getResourceId())
                || !java.util.Objects.equals(event.actorId(), command.getActorId())
                || !java.util.Objects.equals(event.correlationId(), command.getCorrelationId())
                || !java.util.Objects.equals(event.requestId(), phaseKey)
                || event.outcome() != AuditOutcome.SUCCESS) {
            throw new AuditPersistenceUnavailableException("Existing success phase does not match the request identity.", null);
        }
    }

    private void requireMatchingAnchor(AuditEventDocument event) {
        if (event.chainPosition() == null) {
            throw new AuditPersistenceUnavailableException("Existing audit event has no chain position.", null);
        }
        java.util.List<AuditAnchorDocument> anchors = auditAnchorRepository.findByPartitionKeyAndChainPositionBetween(
                event.partitionKey(), event.chainPosition(), event.chainPosition(), 2
        );
        if (anchors.size() != 1) {
            throw new AuditPersistenceUnavailableException("Existing audit event has no unique matching local anchor.", null);
        }
        AuditAnchorDocument anchor = anchors.getFirst();
        if (!java.util.Objects.equals(anchor.partitionKey(), event.partitionKey())
                || anchor.chainPosition() != event.chainPosition()
                || !java.util.Objects.equals(anchor.lastEventHash(), event.eventHash())
                || !java.util.Objects.equals(anchor.previousEventHash(), event.previousEventHash())) {
            throw new AuditPersistenceUnavailableException("Existing audit event has no matching local anchor.", null);
        }
    }

    private void requireCanonical(AuditEventDocument document) {
        if (document != null && !AuditEventHasher.matches(document)) {
            recordAppendAttempt("CHAIN_HEAD_INVALID");
            throw new AuditPersistenceUnavailableException(
                    "Audit event does not satisfy the canonical hash contract.", null);
        }
    }

    private void backoffBeforeRetry() {
        try {
            Thread.sleep(properties.getBackoffMs());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AuditPersistenceUnavailableException("Audit chain lock retry interrupted.", exception);
        }
    }

    private boolean retryBudgetExhausted(long startedAt) {
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        return elapsedMillis + properties.getBackoffMs() > properties.getMaxTotalWaitMs();
    }

    private void recordAppend(String outcome, long startedAt) {
        if (metrics != null) {
            metrics.recordRegulatedMutationLocalAuditChainAppend(outcome);
            metrics.recordRegulatedMutationLocalAuditChainAppendDuration(Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }

    private void recordAppendSafely(String outcome, long startedAt) {
        try {
            recordAppend(outcome, startedAt);
        } catch (RuntimeException ignored) {
            // A metrics backend failure cannot change the result of an already committed append.
        }
    }

    private void recordAppendAttempt(String outcome) {
        if (metrics != null) {
            metrics.recordRegulatedMutationLocalAuditChainAppend(outcome);
        }
    }

    private void recordRetry(String reason) {
        if (metrics != null) {
            metrics.recordRegulatedMutationLocalAuditChainRetry(reason);
        }
    }

    private void recordLockReleaseFailure() {
        if (metrics != null) {
            metrics.recordRegulatedMutationLocalAuditChainLockReleaseFailure();
        }
    }

    private long nextChainPosition(AuditEventDocument previous) {
        if (previous == null) {
            return 1L;
        }
        if (previous.chainPosition() != null && previous.chainPosition() > 0) {
            return previous.chainPosition() + 1L;
        }
        return auditEventRepository.countByPartitionKey(AuditEventDocument.PARTITION_KEY) + 1L;
    }

    private String phaseKey(RegulatedMutationCommandDocument command, String phase) {
        String commandId = command.getId();
        if (commandId == null || commandId.isBlank()) {
            commandId = command.getIdempotencyKey();
        }
        return commandId + ":" + phase;
    }
}
