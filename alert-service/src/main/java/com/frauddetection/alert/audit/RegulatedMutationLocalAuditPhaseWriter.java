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
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class RegulatedMutationLocalAuditPhaseWriter {

    private final AuditEventRepository auditEventRepository;
    private final AuditAnchorRepository auditAnchorRepository;
    private final AuditChainLockRepository lockRepository;
    private final AlertServiceMetrics metrics;
    private final LocalAuditPhaseWriterProperties properties;
    private final ThreadLocal<String> activeChainLockOwner = new ThreadLocal<>();

    @Autowired
    public RegulatedMutationLocalAuditPhaseWriter(
            AuditEventRepository auditEventRepository,
            AuditAnchorRepository auditAnchorRepository,
            AuditChainLockRepository lockRepository,
            AlertServiceMetrics metrics,
            LocalAuditPhaseWriterProperties properties
    ) {
        this.auditEventRepository = auditEventRepository;
        this.auditAnchorRepository = auditAnchorRepository;
        this.lockRepository = lockRepository;
        this.metrics = metrics;
        this.properties = properties == null ? new LocalAuditPhaseWriterProperties() : properties;
    }

    public RegulatedMutationLocalAuditPhaseWriter(
            AuditEventRepository auditEventRepository,
            AuditAnchorRepository auditAnchorRepository,
            AuditChainLockRepository lockRepository
    ) {
        this(auditEventRepository, auditAnchorRepository, lockRepository, null, new LocalAuditPhaseWriterProperties());
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
            recordAppend("DUPLICATE_PHASE", startedAt);
            return existingPhase.auditId();
        }
        if (activeChainLockOwner.get() != null) {
            return appendLocalSuccess(command, action, resourceType, phaseKey, startedAt);
        }
        return withChainLock(() -> appendLocalSuccess(command, action, resourceType, phaseKey, startedAt));
    }

    public <T> T withChainLock(Supplier<T> callback) {
        if (activeChainLockOwner.get() != null) {
            return callback.get();
        }
        String lockOwner = acquireChainLock();
        activeChainLockOwner.set(lockOwner);
        try {
            return callback.get();
        } finally {
            activeChainLockOwner.remove();
            try {
                lockRepository.release(AuditEventDocument.PARTITION_KEY, lockOwner);
            } catch (DataAccessException ignored) {
                recordAppendAttempt("LOCK_RELEASE_FAILED");
                recordLockReleaseFailure();
            }
        }
    }

    private String acquireChainLock() {
        long startedAt = System.nanoTime();
        String lockOwner = UUID.randomUUID().toString();
        for (int attempt = 1; attempt <= properties.getMaxAppendAttempts(); attempt++) {
            try {
                lockRepository.acquire(AuditEventDocument.PARTITION_KEY, lockOwner);
                return lockOwner;
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
            AuditAction action,
            AuditResourceType resourceType,
            String phaseKey,
            long startedAt
    ) {
        boolean auditEventInserted = false;
        try {
            AuditEventDocument existingPhaseEvent = auditEventRepository.findByRequestId(phaseKey).orElse(null);
            if (existingPhaseEvent != null) {
                recordAppend("DUPLICATE_PHASE", startedAt);
                return existingPhaseEvent.auditId();
            }
            AuditEventDocument previous = auditEventRepository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY)
                    .orElse(null);
            String previousHash = previous == null ? null : previous.eventHash();
            long chainPosition = nextChainPosition(previous);
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
            AuditEventDocument document = auditEventRepository.insert(AuditEventDocument.from(
                    UUID.randomUUID().toString(),
                    event,
                    previousHash,
                    chainPosition
            ));
            auditEventInserted = true;
            auditAnchorRepository.insert(AuditAnchorDocument.from(UUID.randomUUID().toString(), document));
            recordAppend("SUCCESS", startedAt);
            return document.auditId();
        } catch (DuplicateKeyException duplicate) {
            return auditEventRepository.findByRequestId(phaseKey)
                    .map(document -> {
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
