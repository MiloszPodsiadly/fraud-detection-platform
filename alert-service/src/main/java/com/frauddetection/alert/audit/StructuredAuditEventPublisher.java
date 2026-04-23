package com.frauddetection.alert.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class StructuredAuditEventPublisher implements AuditEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(StructuredAuditEventPublisher.class);

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
    }
}
