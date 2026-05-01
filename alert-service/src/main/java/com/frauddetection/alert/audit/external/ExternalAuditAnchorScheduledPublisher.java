package com.frauddetection.alert.audit.external;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class ExternalAuditAnchorScheduledPublisher {

    private final ExternalAuditAnchorPublisher publisher;
    private final boolean publicationEnabled;

    ExternalAuditAnchorScheduledPublisher(
            ExternalAuditAnchorPublisher publisher,
            @Value("${app.audit.external-anchoring.publication.enabled:${app.audit.external-anchoring.enabled:false}}") boolean publicationEnabled
    ) {
        this.publisher = publisher;
        this.publicationEnabled = publicationEnabled;
    }

    @Scheduled(
            fixedDelayString = "${app.audit.external-anchoring.publish-delay-ms:60000}",
            initialDelayString = "${app.audit.external-anchoring.initial-delay-ms:10000}"
    )
    void publishAnchors() {
        if (!publicationEnabled) {
            return;
        }
        publisher.publishDefaultWindow();
    }
}
