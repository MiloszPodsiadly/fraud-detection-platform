package com.frauddetection.alert.audit;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PersistentAuditEventPublisher implements AuditEventPublisher {

    private final AuditEventRepository repository;
    private final AuditAnchorRepository anchorRepository;
    private final AlertServiceMetrics metrics;

    public PersistentAuditEventPublisher(
            AuditEventRepository repository,
            AuditAnchorRepository anchorRepository,
            AlertServiceMetrics metrics
    ) {
        this.repository = repository;
        this.anchorRepository = anchorRepository;
        this.metrics = metrics;
    }

    @Override
    public synchronized void publish(AuditEvent event) {
        try {
            String previousHash = repository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY)
                    .map(AuditEventDocument::eventHash)
                    .orElse(null);
            AuditEventDocument document = repository.insert(AuditEventDocument.from(UUID.randomUUID().toString(), event, previousHash));
            long chainPosition = repository.countByPartitionKey(document.partitionKey());
            anchorRepository.insert(AuditAnchorDocument.from(UUID.randomUUID().toString(), document, chainPosition));
            metrics.recordPlatformAuditEventPersisted(event.action(), event.outcome());
        } catch (DataAccessException exception) {
            metrics.recordPlatformAuditPersistenceFailure(event.action());
            throw new AuditPersistenceUnavailableException();
        }
    }
}
