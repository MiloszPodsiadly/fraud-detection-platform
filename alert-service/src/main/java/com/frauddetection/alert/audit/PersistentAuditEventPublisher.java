package com.frauddetection.alert.audit;

import com.mongodb.MongoException;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorPublicationRequiredException;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorPublisher;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PersistentAuditEventPublisher implements AuditEventPublisher {

    private static final int DEFAULT_MAX_APPEND_ATTEMPTS = 2_000;
    private static final long DEFAULT_LOCK_RETRY_BACKOFF_MILLIS = 5L;

    private final AuditEventRepository repository;
    private final AuditAnchorRepository anchorRepository;
    private final AuditChainLockRepository lockRepository;
    private final AlertServiceMetrics metrics;
    private final AuditMongoTransactionRunner transactionRunner;
    private final ExternalAuditAnchorPublisher externalAnchorPublisher;
    private final boolean externalPublicationRequired;
    private final boolean externalPublicationFailClosed;
    private final int maxAppendAttempts;
    private final long lockRetryBackoffMillis;

    public PersistentAuditEventPublisher(
            AuditEventRepository repository,
            AuditAnchorRepository anchorRepository,
            AuditChainLockRepository lockRepository,
            AlertServiceMetrics metrics,
            ObjectProvider<ExternalAuditAnchorPublisher> externalAnchorPublisher,
            boolean externalPublicationRequired,
            boolean externalPublicationFailClosed
    ) {
        this(repository, anchorRepository, lockRepository, metrics,
                externalAnchorPublisher == null ? null : externalAnchorPublisher.getIfAvailable(),
                externalPublicationRequired, externalPublicationFailClosed, DEFAULT_MAX_APPEND_ATTEMPTS,
                DEFAULT_LOCK_RETRY_BACKOFF_MILLIS, new AuditMongoTransactionRunner());
    }

    @Autowired
    public PersistentAuditEventPublisher(
            AuditEventRepository repository,
            AuditAnchorRepository anchorRepository,
            AuditChainLockRepository lockRepository,
            AlertServiceMetrics metrics,
            ObjectProvider<ExternalAuditAnchorPublisher> externalAnchorPublisher,
            @Value("${app.audit.external-anchoring.publication.required:false}") boolean externalPublicationRequired,
            @Value("${app.audit.external-anchoring.publication.fail-closed:false}") boolean externalPublicationFailClosed,
            AuditMongoTransactionRunner transactionRunner
    ) {
        this(
                repository,
                anchorRepository,
                lockRepository,
                metrics,
                externalAnchorPublisher == null ? null : externalAnchorPublisher.getIfAvailable(),
                externalPublicationRequired,
                externalPublicationFailClosed,
                DEFAULT_MAX_APPEND_ATTEMPTS,
                DEFAULT_LOCK_RETRY_BACKOFF_MILLIS,
                transactionRunner
        );
    }

    PersistentAuditEventPublisher(
            AuditEventRepository repository,
            AuditAnchorRepository anchorRepository,
            AuditChainLockRepository lockRepository,
            AlertServiceMetrics metrics,
            int maxAppendAttempts,
            long lockRetryBackoffMillis
    ) {
        this(repository, anchorRepository, lockRepository, metrics, null, false, false, maxAppendAttempts,
                lockRetryBackoffMillis, new AuditMongoTransactionRunner());
    }

    PersistentAuditEventPublisher(
            AuditEventRepository repository,
            AuditAnchorRepository anchorRepository,
            AuditChainLockRepository lockRepository,
            AlertServiceMetrics metrics
    ) {
        this(repository, anchorRepository, lockRepository, metrics, null, false, false, DEFAULT_MAX_APPEND_ATTEMPTS,
                DEFAULT_LOCK_RETRY_BACKOFF_MILLIS, new AuditMongoTransactionRunner());
    }

    PersistentAuditEventPublisher(
            AuditEventRepository repository,
            AuditAnchorRepository anchorRepository,
            AuditChainLockRepository lockRepository,
            AlertServiceMetrics metrics,
            AuditMongoTransactionRunner transactionRunner
    ) {
        this(repository, anchorRepository, lockRepository, metrics, null, false, false, DEFAULT_MAX_APPEND_ATTEMPTS,
                DEFAULT_LOCK_RETRY_BACKOFF_MILLIS, transactionRunner);
    }

    PersistentAuditEventPublisher(
            AuditEventRepository repository,
            AuditAnchorRepository anchorRepository,
            AuditChainLockRepository lockRepository,
            AlertServiceMetrics metrics,
            ExternalAuditAnchorPublisher externalAnchorPublisher,
            boolean externalAnchoringEnabled,
            int maxAppendAttempts,
            long lockRetryBackoffMillis
    ) {
        this(repository, anchorRepository, lockRepository, metrics, externalAnchorPublisher, externalAnchoringEnabled,
                externalAnchoringEnabled, maxAppendAttempts, lockRetryBackoffMillis, new AuditMongoTransactionRunner());
    }

    PersistentAuditEventPublisher(
            AuditEventRepository repository,
            AuditAnchorRepository anchorRepository,
            AuditChainLockRepository lockRepository,
            AlertServiceMetrics metrics,
            ExternalAuditAnchorPublisher externalAnchorPublisher,
            boolean externalPublicationRequired,
            boolean externalPublicationFailClosed,
            int maxAppendAttempts,
            long lockRetryBackoffMillis
    ) {
        this(repository, anchorRepository, lockRepository, metrics, externalAnchorPublisher, externalPublicationRequired,
                externalPublicationFailClosed, maxAppendAttempts, lockRetryBackoffMillis, new AuditMongoTransactionRunner());
    }

    PersistentAuditEventPublisher(
            AuditEventRepository repository,
            AuditAnchorRepository anchorRepository,
            AuditChainLockRepository lockRepository,
            AlertServiceMetrics metrics,
            ExternalAuditAnchorPublisher externalAnchorPublisher,
            boolean externalPublicationRequired,
            boolean externalPublicationFailClosed,
            int maxAppendAttempts,
            long lockRetryBackoffMillis,
            AuditMongoTransactionRunner transactionRunner
    ) {
        this.repository = repository;
        this.anchorRepository = anchorRepository;
        this.lockRepository = lockRepository;
        this.metrics = metrics;
        this.transactionRunner = transactionRunner == null ? new AuditMongoTransactionRunner() : transactionRunner;
        this.externalAnchorPublisher = externalAnchorPublisher;
        if (externalPublicationFailClosed && !externalPublicationRequired) {
            throw new IllegalStateException("app.audit.external-anchoring.publication.fail-closed=true requires publication.required=true.");
        }
        this.externalPublicationRequired = externalPublicationRequired;
        this.externalPublicationFailClosed = externalPublicationFailClosed;
        this.maxAppendAttempts = maxAppendAttempts;
        this.lockRetryBackoffMillis = lockRetryBackoffMillis;
    }

    @Override
    public void publish(AuditEvent event) {
        if (externalPublicationRequired && externalPublicationFailClosed) {
            publishRequiredEvent(event);
            return;
        }
        appendWithRetry(event);
    }

    private void publishRequiredEvent(AuditEvent event) {
        AppendResult appended = appendWithRetry(event);
        if (transactionRunner.transactionActive()) {
            transactionRunner.afterCommit(() -> publishRequiredAfterLocalCommit(event, appended));
            return;
        }
        publishRequiredAfterLocalCommit(event, appended);
    }

    private void publishRequiredAfterLocalCommit(AuditEvent event, AppendResult appended) {
        try {
            publishRequiredExternalAnchor(appended.anchor());
        } catch (ExternalAuditAnchorPublicationRequiredException exception) {
            appendCompensatingExternalAnchorFailure(event, appended.document(), exception);
            throw exception;
        }
    }

    private AppendResult appendWithRetry(AuditEvent event) {
        String auditId = UUID.randomUUID().toString();
        String anchorId = UUID.randomUUID().toString();
        for (int attempt = 1; attempt <= maxAppendAttempts; attempt++) {
            try {
                AppendResult appended = appendUnderLock(event, auditId, anchorId);
                if (appended.newlyInserted()) {
                    transactionRunner.afterCommit(() -> metrics.recordPlatformAuditEventPersisted(event.action(), event.outcome()));
                }
                return appended;
            } catch (AuditChainConflictException exception) {
                if (attempt == maxAppendAttempts) {
                    metrics.recordPlatformAuditChainConflict();
                    metrics.recordPlatformAuditPersistenceFailure(event.action());
                    throw new AuditPersistenceUnavailableException();
                }
                backoffBeforeRetry();
            } catch (RuntimeException exception) {
                try {
                    AppendResult reconciled = reconcileCommittedAppend(event, auditId);
                    if (reconciled != null) {
                        if (reconciled.newlyInserted()) {
                            transactionRunner.afterCommit(() -> metrics.recordPlatformAuditEventPersisted(event.action(), event.outcome()));
                        }
                        return reconciled;
                    }
                } catch (RuntimeException reconciliationFailure) {
                    exception.addSuppressed(reconciliationFailure);
                }
                if (isRetryableTransactionFailure(exception)) {
                    if (attempt == maxAppendAttempts) {
                        metrics.recordPlatformAuditChainConflict();
                        metrics.recordPlatformAuditPersistenceFailure(event.action());
                        throw new AuditPersistenceUnavailableException("Audit transaction retry budget exhausted.", exception);
                    }
                    backoffBeforeRetry();
                    continue;
                }
                throw exception;
            }
        }
        throw new AuditPersistenceUnavailableException();
    }

    private AppendResult appendUnderLock(AuditEvent event, String auditId, String anchorId) {
        String lockOwner = UUID.randomUUID().toString();
        AuditChainLockDocument lease = null;
        try {
            lease = lockRepository.acquire(AuditEventDocument.PARTITION_KEY, lockOwner);
            AuditChainLockDocument acquiredLease = lease;
            return transactionRunner.required(() -> {
                if (transactionRunner.transactionActive()) {
                    lockRepository.fence(acquiredLease);
                }
                return appendOnce(event, auditId, anchorId);
            });
        } catch (AuditChainConflictException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            metrics.recordPlatformAuditPersistenceFailure(event.action());
            throw new AuditPersistenceUnavailableException(null, exception);
        } finally {
            if (lease != null) {
                AuditChainLockDocument acquiredLease = lease;
                AtomicBoolean released = new AtomicBoolean();
                Runnable release = () -> {
                    if (released.get()) return;
                    try {
                        transactionRunner.requiresNew(() -> {
                            lockRepository.release(acquiredLease);
                            return null;
                        });
                        released.set(true);
                    } catch (RuntimeException exception) {
                        metrics.recordPlatformAuditChainConflict();
                    }
                };
                // Unlock after commit before later after-commit callbacks may need a second append.
                transactionRunner.afterCommit(release);
                transactionRunner.afterCompletion(release);
            }
        }
    }

    private AppendResult appendOnce(AuditEvent event, String auditId, String anchorId) {
        boolean auditEventInserted = false;
        try {
            AuditEventDocument duplicate = findDuplicateRequest(event);
            if (duplicate != null) {
                return requireReconciledAppend(event, duplicate);
            }
            AuditEventDocument previous = repository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY).orElse(null);
            if (previous != null && !AuditEventHasher.matches(previous)) {
                metrics.recordPlatformAuditPersistenceFailure(event.action());
                throw new AuditPersistenceUnavailableException(
                        "Audit chain head does not satisfy the canonical hash contract.", null);
            }
            String previousHash = previous == null ? null : previous.eventHash();
            long chainPosition = nextChainPosition(previous);
            AuditEventDocument document = repository.insert(AuditEventDocument.from(
                    auditId,
                    event,
                    previousHash,
                    chainPosition
            ));
            auditEventInserted = true;
            AuditAnchorDocument anchor = anchorRepository.insert(AuditAnchorDocument.from(anchorId, document));
            return new AppendResult(document, anchor, true);
        } catch (AuditChainConflictException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            if (auditEventInserted) {
                metrics.recordPlatformAuditAnchorWriteFailure();
            }
            metrics.recordPlatformAuditPersistenceFailure(event.action());
            throw new AuditPersistenceUnavailableException(null, exception);
        }
    }

    private boolean isRetryableTransactionFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof MongoException mongoException
                    && (mongoException.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)
                    || mongoException.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private AuditEventDocument findDuplicateRequest(AuditEvent event) {
        if (event.requestId() == null || event.requestId().isBlank()) {
            return null;
        }
        return repository.findByRequestId(event.requestId()).orElse(null);
    }

    private AppendResult reconcileCommittedAppend(AuditEvent event, String auditId) {
        AuditEventDocument existing = event.requestId() == null || event.requestId().isBlank()
                ? repository.findByAuditId(auditId).orElse(null)
                : repository.findByRequestId(event.requestId()).orElse(null);
        if (existing == null) {
            return null;
        }
        AppendResult reconciled = requireReconciledAppend(event, existing);
        return java.util.Objects.equals(existing.auditId(), auditId)
                ? new AppendResult(reconciled.document(), reconciled.anchor(), true)
                : reconciled;
    }

    private AppendResult requireReconciledAppend(AuditEvent event, AuditEventDocument existing) {
        if (existing.chainPosition() == null || existing.chainPosition() < 1L) {
            throw new AuditPersistenceUnavailableException("Existing audit event has no valid chain position.", null);
        }
        AuditEventDocument expected = AuditEventDocument.from(
                existing.auditId(), event, existing.previousEventHash(), existing.chainPosition()
        );
        boolean sameEvent = AuditEventHasher.matches(existing)
                && expected.eventHash().equals(existing.eventHash());
        if (!sameEvent) {
            metrics.recordPlatformAuditPersistenceFailure(event.action());
            throw new AuditPersistenceUnavailableException("Existing audit request does not match the canonical event identity.", null);
        }
        List<AuditAnchorDocument> anchors = anchorRepository.findByPartitionKeyAndChainPositionBetween(
                existing.partitionKey(), existing.chainPosition(), existing.chainPosition(), 2
        );
        if (anchors.size() != 1 || !anchorMatches(anchors.getFirst(), existing)) {
            metrics.recordPlatformAuditPersistenceFailure(event.action());
            throw new AuditPersistenceUnavailableException("Existing audit event has no matching local anchor.", null);
        }
        return new AppendResult(existing, anchors.getFirst(), false);
    }

    private boolean anchorMatches(AuditAnchorDocument anchor, AuditEventDocument event) {
        return anchor.chainPosition() == event.chainPosition()
                && AuditEventDocument.PARTITION_KEY.equals(anchor.partitionKey())
                && event.eventHash().equals(anchor.lastEventHash())
                && java.util.Objects.equals(event.previousEventHash(), anchor.previousEventHash());
    }

    private void backoffBeforeRetry() {
        try {
            Thread.sleep(lockRetryBackoffMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AuditPersistenceUnavailableException();
        }
    }

    private long nextChainPosition(AuditEventDocument previous) {
        if (previous == null) {
            return 1L;
        }
        if (previous.chainPosition() != null && previous.chainPosition() > 0) {
            return previous.chainPosition() + 1L;
        }
        return repository.countByPartitionKey(AuditEventDocument.PARTITION_KEY) + 1L;
    }

    private void publishRequiredExternalAnchor(AuditAnchorDocument anchor) {
        if (externalAnchorPublisher == null) {
            throw new ExternalAuditAnchorPublicationRequiredException("UNAVAILABLE");
        }
        externalAnchorPublisher.publishRequired(anchor);
    }

    private void appendCompensatingExternalAnchorFailure(
            AuditEvent originalEvent,
            AuditEventDocument attemptedDocument,
            ExternalAuditAnchorPublicationRequiredException publicationException
    ) {
        try {
            AuditEvent compensatingEvent = new AuditEvent(
                    originalEvent.actor(),
                    AuditAction.EXTERNAL_ANCHOR_REQUIRED_FAILED,
                    AuditResourceType.AUDIT_EVENT,
                    attemptedDocument.auditId(),
                    originalEvent.timestamp(),
                    originalEvent.correlationId(),
                    AuditOutcome.ABORTED_EXTERNAL_ANCHOR_REQUIRED,
                    ExternalAuditAnchorPublicationRequiredException.class.getSimpleName() + ":" + publicationException.reason()
            );
            transactionRunner.requiresNew(() -> {
                appendWithRetry(compensatingEvent);
                return null;
            });
        } catch (DataAccessException compensationFailure) {
            metrics.recordPlatformAuditPersistenceFailure(AuditAction.EXTERNAL_ANCHOR_REQUIRED_FAILED);
        } catch (AuditPersistenceUnavailableException compensationFailure) {
            metrics.recordPlatformAuditPersistenceFailure(AuditAction.EXTERNAL_ANCHOR_REQUIRED_FAILED);
        }
    }

    private record AppendResult(AuditEventDocument document, AuditAnchorDocument anchor, boolean newlyInserted) {
    }

}
