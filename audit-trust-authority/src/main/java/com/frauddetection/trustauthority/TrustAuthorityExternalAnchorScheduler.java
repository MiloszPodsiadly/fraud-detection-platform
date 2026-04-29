package com.frauddetection.trustauthority;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.trust-authority.external-anchoring", name = "enabled", havingValue = "true")
class TrustAuthorityExternalAnchorScheduler {

    private static final Logger log = LoggerFactory.getLogger(TrustAuthorityExternalAnchorScheduler.class);

    private final TrustAuthorityAuditSink auditSink;
    private final ExternalAnchorPublisher publisher;

    TrustAuthorityExternalAnchorScheduler(TrustAuthorityAuditSink auditSink, ExternalAnchorPublisher publisher) {
        this.auditSink = auditSink;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${app.trust-authority.external-anchoring.fixed-delay-ms:900000}")
    void publishAuditHeadHook() {
        TrustAuthorityAuditHeadResponse head = auditSink.head();
        if (!"AVAILABLE".equals(head.status())) {
            log.info("Trust authority external anchor hook skipped status={}", head.status());
            return;
        }
        log.info(
                "Trust authority external anchor hook observed head source={} proof_type={} integrity_hint={} chain_position={} event_hash={} occurred_at={}",
                head.source(),
                head.proofType(),
                head.integrityHint(),
                head.chainPosition(),
                head.eventHash(),
                head.occurredAt()
        );
        publisher.publish(head);
    }
}
