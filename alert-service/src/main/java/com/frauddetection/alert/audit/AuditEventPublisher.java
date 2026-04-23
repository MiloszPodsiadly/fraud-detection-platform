package com.frauddetection.alert.audit;

public interface AuditEventPublisher {

    void publish(AuditEvent event);
}
