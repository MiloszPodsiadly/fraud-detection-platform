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
            receipts.compactExpired(50);
            receipts.recoverRawPublications(publisher, properties.batchSize());
        } catch (RuntimeException unavailable) {
            log.warn("Durable RAW publication recovery is temporarily unavailable.");
        }
    }
}
