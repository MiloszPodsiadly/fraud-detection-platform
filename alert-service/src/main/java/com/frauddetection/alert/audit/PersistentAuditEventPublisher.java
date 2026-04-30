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
    private final boolean externalAnchoringEnabled;
    private final int maxAppendAttempts;
    private final long lockRetryBackoffMillis;

    @Autowired
    public PersistentAuditEventPublisher(
            AuditEventRepository repository,
            AuditAnchorRepository anchorRepository,
            AuditChainLockRepository lockRepository,
            AlertServiceMetrics metrics,
            ObjectProvider<ExternalAuditAnchorPublisher> externalAnchorPublisher,
            @Value("${app.audit.external-anchoring.enabled:false}") boolean externalAnchoringEnabled
    ) {
        this(
                repository,
                anchorRepository,
                lockRepository,
                metrics,
                externalAnchorPublisher == null ? null : externalAnchorPublisher.getIfAvailable(),
                externalAnchoringEnabled,
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
        this.repository = repository;
        this.anchorRepository = anchorRepository;
        this.lockRepository = lockRepository;
        this.metrics = metrics;
        this.externalAnchorPublisher = externalAnchorPublisher;
        this.externalAnchoringEnabled = externalAnchoringEnabled;
        this.maxAppendAttempts = maxAppendAttempts;
        this.lockRetryBackoffMillis = lockRetryBackoffMillis;
    }

    @Override
    public void publish(AuditEvent event) {
        for (int attempt = 1; attempt <= maxAppendAttempts; attempt++) {
            try {
                appendOnce(event);
                return;
            } catch (AuditChainConflictException exception) {
                if (attempt == maxAppendAttempts) {
                    metrics.recordPlatformAuditChainConflict();
                    metrics.recordPlatformAuditPersistenceFailure(event.action());
                    throw new AuditPersistenceUnavailableException();
                }
                backoffBeforeRetry();
            }
        }
    }

    private void appendOnce(AuditEvent event) {
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
            publishExternalAnchorIfRequired(anchor);
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

    private void publishExternalAnchorIfRequired(AuditAnchorDocument anchor) {
        if (!externalAnchoringEnabled) {
            return;
        }
        if (externalAnchorPublisher == null) {
            throw new ExternalAuditAnchorPublicationRequiredException("UNAVAILABLE");
        }
        externalAnchorPublisher.publishRequired(anchor);
    }

}
