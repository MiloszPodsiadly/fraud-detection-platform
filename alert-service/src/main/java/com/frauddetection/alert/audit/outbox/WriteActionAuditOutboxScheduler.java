package com.frauddetection.alert.audit.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@ConditionalOnProperty(
        prefix = "app.audit.outbox.publisher",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class WriteActionAuditOutboxScheduler {

    private final WriteActionAuditOutboxPublisher publisher;

    public WriteActionAuditOutboxScheduler(WriteActionAuditOutboxPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher is required");
    }

    @Scheduled(fixedDelayString = "${app.audit.outbox.publisher.fixed-delay-ms:30000}")
    public void publishPendingAuditIntents() {
        publisher.publishPending();
    }
}
