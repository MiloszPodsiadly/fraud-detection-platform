package com.frauddetection.alert.audit;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class StructuredAuditEventPublisher implements AuditEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(StructuredAuditEventPublisher.class);
    private final AlertServiceMetrics metrics;

    public StructuredAuditEventPublisher(AlertServiceMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void publish(AuditEvent event) {
        log.atInfo()
                .addKeyValue("auditAction", event.action())
                .addKeyValue("resourceType", event.resourceType())
                .addKeyValue("resourceId", event.resourceId())
                .addKeyValue("actorUserId", event.actor().userId())
                .addKeyValue("actorRoles", event.actor().roles())
                .addKeyValue("actorAuthorities", event.actor().authorities())
                .addKeyValue("auditTimestamp", event.timestamp())
                .addKeyValue("correlationId", event.correlationId())
                .addKeyValue("auditOutcome", event.outcome())
                .addKeyValue("failureReason", event.failureReason())
                .log("Audit event recorded.");
        metrics.recordAuditEventPublished(event.action(), event.outcome());
    }
}
