package com.frauddetection.ingest.source;

import com.frauddetection.ingest.messaging.TransactionRawEventProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Independently drains durable RAW intents, including work left by a prior process. */
final class SourceRawPublicationRecovery {
    private static final Logger log = LoggerFactory.getLogger(SourceRawPublicationRecovery.class);
    private final SourceReceiptStore receipts;
    private final TransactionRawEventProducer publisher;
    private final SourceRawPublicationProperties properties;

    SourceRawPublicationRecovery(SourceReceiptStore receipts, TransactionRawEventProducer publisher,
                                 SourceRawPublicationProperties properties) {
        this.receipts = receipts;
        this.publisher = publisher;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${fraud.source-raw-publications.poll-interval:PT1S}")
    void recover() {
        try {
            receipts.recoverRawPublications(publisher, properties.batchSize());
        } catch (RuntimeException unavailable) {
            log.warn("Durable RAW publication recovery is temporarily unavailable.");
        }
        try {
            receipts.compactExpired(50);
        } catch (RuntimeException unavailable) {
            log.warn("Expired RAW publication compaction is temporarily unavailable.");
        }
    }

    @Scheduled(fixedDelayString = "${fraud.source-raw-publications.health-interval:PT1M}")
    void reportHealth() {
        try {
            SourceReceiptStore.RecoveryHealth health = receipts.recoveryHealth();
            if (health.quarantinedPublishFailed() > 0 || health.quarantinedAuthorityDenied() > 0
                    || health.quarantinedPayloadUnavailable() > 0 || health.redriveEpochExhausted() > 0) {
                log.atWarn().addKeyValue("pendingCount", health.pending()).addKeyValue("leasedCount", health.leased())
                        .addKeyValue("quarantinedPublishFailedCount", health.quarantinedPublishFailed())
                        .addKeyValue("quarantinedAuthorityDeniedCount", health.quarantinedAuthorityDenied())
                        .addKeyValue("quarantinedPayloadUnavailableCount", health.quarantinedPayloadUnavailable())
                        .addKeyValue("redriveEpochExhaustedCount", health.redriveEpochExhausted())
                        .addKeyValue("oldestRecoverableAgeSeconds", health.oldestRecoverableAgeSeconds())
                        .log("Durable RAW publication queue has quarantined intents.");
            } else {
                log.atInfo().addKeyValue("pendingCount", health.pending()).addKeyValue("leasedCount", health.leased())
                        .addKeyValue("oldestRecoverableAgeSeconds", health.oldestRecoverableAgeSeconds())
                        .log("Durable RAW publication recovery health snapshot.");
            }
        } catch (RuntimeException unavailable) {
            log.warn("Durable RAW publication recovery health is temporarily unavailable.");
        }
    }
}
