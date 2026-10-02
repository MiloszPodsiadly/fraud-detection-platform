package com.frauddetection.alert.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OutboxOperationalControls {

    private final boolean publisherEnabled;
    private final boolean recoveryEnabled;

    public OutboxOperationalControls(
            @Value("${app.outbox.publisher.enabled:true}") boolean publisherEnabled,
            @Value("${app.outbox.recovery.enabled:true}") boolean recoveryEnabled
    ) {
        this.publisherEnabled = publisherEnabled;
        this.recoveryEnabled = recoveryEnabled;
    }

    public boolean publisherEnabled() {
        return publisherEnabled;
    }

    public boolean recoveryEnabled() {
        return recoveryEnabled;
    }

    public void requireRecoveryEnabled() {
        if (!recoveryEnabled) {
            throw new IllegalStateException("Transactional outbox recovery is disabled.");
        }
    }
}
