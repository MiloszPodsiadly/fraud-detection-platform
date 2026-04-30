package com.frauddetection.alert.audit;

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

import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PersistentAuditEventPublisher implements AuditEventPublisher {

    private static final int DEFAULT_MAX_APPEND_ATTEMPTS = 2_000;
    private static final long DEFAULT_LOCK_RETRY_BACKOFF_MILLIS = 5L;

    private final AuditEventRepository repository;
    private final AuditAnchorRepository anchorRepository;
    private final AuditChainLockRepository lockRepository;
    private final AlertServiceMetrics metrics;
    private final ExternalAuditAnchorPublisher externalAnchorPublisher;
    private final boolean externalPublicationRequired;
    private final boolean externalPublicationFailClosed;
    private final int maxAppendAttempts;
    private final long lockRetryBackoffMillis;

    @Autowired
    public PersistentAuditEventPublisher(
            AuditEventRepository repository,
            AuditAnchorRepository anchorRepository,
            AuditChainLockRepository lockRepository,
            AlertServiceMetrics metrics,
            ObjectProvider<ExternalAuditAnchorPublisher> externalAnchorPublisher,
            @Value("${app.audit.external-anchoring.publication.required:${app.audit.external-anchoring.enabled:false}}") boolean externalPublicationRequired,
            @Value("${app.audit.external-anchoring.publication.fail-closed:${app.audit.external-anchoring.publication.required:${app.audit.external-anchoring.enabled:false}}}") boolean externalPublicationFailClosed
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
                DEFAULT_LOCK_RETRY_BACKOFF_MILLIS
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
        this(repository, anchorRepository, lockRepository, metrics, null, false, maxAppendAttempts, lockRetryBackoffMillis);
    }

    PersistentAuditEventPublisher(
            AuditEventRepository repository,
            AuditAnchorRepository anchorRepository,
            AuditChainLockRepository lockRepository,
            AlertServiceMetrics metrics
    ) {
        this(repository, anchorRepository, lockRepository, metrics, null, false, DEFAULT_MAX_APPEND_ATTEMPTS, DEFAULT_LOCK_RETRY_BACKOFF_MILLIS);
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
        this(repository, anchorRepository, lockRepository, metrics, externalAnchorPublisher, externalAnchoringEnabled, externalAnchoringEnabled, maxAppendAttempts, lockRetryBackoffMillis);
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
        this.repository = repository;
        this.anchorRepository = anchorRepository;
        this.lockRepository = lockRepository;
        this.metrics = metrics;
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
        if (requiresAttemptedExternalAnchorFlow(event)) {
            publishRequiredSuccess(event);
            return;
        }
        appendWithRetry(event, externalPublicationRequired && externalPublicationFailClosed);
    }

    private void publishRequiredSuccess(AuditEvent event) {
        AuditEvent attemptedEvent = event.withOutcome(AuditOutcome.ATTEMPTED, null);
        AppendResult attempted = appendWithRetry(attemptedEvent, false);
        try {
            publishRequiredExternalAnchor(attempted.anchor());
        } catch (ExternalAuditAnchorPublicationRequiredException exception) {
            appendCompensatingExternalAnchorFailure(event, attempted.document(), exception);
            throw exception;
        }
        AppendResult success = appendWithRetry(event, false);
        try {
            publishRequiredExternalAnchor(success.anchor());
        } catch (ExternalAuditAnchorPublicationRequiredException exception) {
            appendCompensatingExternalAnchorFailure(event, success.document(), exception);
            throw exception;
        }
    }

    private boolean requiresAttemptedExternalAnchorFlow(AuditEvent event) {
        return externalPublicationRequired
                && externalPublicationFailClosed
                && event.outcome() == AuditOutcome.SUCCESS;
    }

    private AppendResult appendWithRetry(AuditEvent event, boolean publishRequired) {
        for (int attempt = 1; attempt <= maxAppendAttempts; attempt++) {
            try {
                return appendOnce(event, publishRequired);
            } catch (AuditChainConflictException exception) {
                if (attempt == maxAppendAttempts) {
                    metrics.recordPlatformAuditChainConflict();
                    metrics.recordPlatformAuditPersistenceFailure(event.action());
                    throw new AuditPersistenceUnavailableException();
                }
                backoffBeforeRetry();
            }
        }
        throw new AuditPersistenceUnavailableException();
    }

    private AppendResult appendOnce(AuditEvent event, boolean publishRequired) {
        String lockOwner = UUID.randomUUID().toString();
        boolean lockAcquired = false;
        boolean auditEventInserted = false;
        try {
            lockRepository.acquire(AuditEventDocument.PARTITION_KEY, lockOwner);
            lockAcquired = true;
            AuditEventDocument previous = repository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY).orElse(null);
            String previousHash = previous == null ? null : previous.eventHash();
            long chainPosition = nextChainPosition(previous);
            AuditEventDocument document = repository.insert(AuditEventDocument.from(
                    UUID.randomUUID().toString(),
                    event,
                    previousHash,
                    chainPosition
            ));
            auditEventInserted = true;
            AuditAnchorDocument anchor = anchorRepository.insert(AuditAnchorDocument.from(UUID.randomUUID().toString(), document));
            metrics.recordPlatformAuditEventPersisted(event.action(), event.outcome());
            if (publishRequired) {
                publishRequiredExternalAnchor(anchor);
            }
            return new AppendResult(document, anchor);
        } catch (AuditChainConflictException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            if (auditEventInserted) {
                metrics.recordPlatformAuditAnchorWriteFailure();
            }
            metrics.recordPlatformAuditPersistenceFailure(event.action());
            throw new AuditPersistenceUnavailableException();
        } finally {
            if (lockAcquired) {
                try {
                    lockRepository.release(AuditEventDocument.PARTITION_KEY, lockOwner);
                } catch (DataAccessException exception) {
                    metrics.recordPlatformAuditChainConflict();
                }
            }
        }
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
            appendWithRetry(compensatingEvent, false);
        } catch (DataAccessException compensationFailure) {
            metrics.recordPlatformAuditPersistenceFailure(AuditAction.EXTERNAL_ANCHOR_REQUIRED_FAILED);
        } catch (AuditPersistenceUnavailableException compensationFailure) {
            metrics.recordPlatformAuditPersistenceFailure(AuditAction.EXTERNAL_ANCHOR_REQUIRED_FAILED);
        }
    }

    private record AppendResult(AuditEventDocument document, AuditAnchorDocument anchor) {
    }

}
