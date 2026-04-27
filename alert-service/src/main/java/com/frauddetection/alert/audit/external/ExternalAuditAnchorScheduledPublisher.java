package com.frauddetection.alert.audit.external;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.audit.external-anchoring.enabled", havingValue = "true")
class ExternalAuditAnchorScheduledPublisher {

    private final ExternalAuditAnchorPublisher publisher;

    ExternalAuditAnchorScheduledPublisher(ExternalAuditAnchorPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(
            fixedDelayString = "${app.audit.external-anchoring.publish-delay-ms:60000}",
            initialDelayString = "${app.audit.external-anchoring.initial-delay-ms:10000}"
    )
    void publishAnchors() {
        publisher.publishDefaultWindow();
    }
}
