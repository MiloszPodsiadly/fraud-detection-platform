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
    private final AlertServiceMetrics metrics;

    public PersistentAuditEventPublisher(AuditEventRepository repository, AlertServiceMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    @Override
    public void publish(AuditEvent event) {
        try {
            repository.save(AuditEventDocument.from(UUID.randomUUID().toString(), event));
            metrics.recordPlatformAuditEventPersisted(event.action(), event.outcome());
        } catch (DataAccessException exception) {
            metrics.recordPlatformAuditPersistenceFailure(event.action());
            throw new AuditPersistenceUnavailableException();
        }
    }
}
