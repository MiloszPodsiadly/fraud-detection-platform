package com.frauddetection.ingest.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;

/** One-shot operator process. It is deliberately unavailable from a running HTTP service. */
final class SourceRawPublicationRedriveRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SourceRawPublicationRedriveRunner.class);
    private final SourceReceiptStore receipts;
    private final SourceRawPublicationRedriveProperties properties;
    private final ConfigurableApplicationContext context;

    SourceRawPublicationRedriveRunner(SourceReceiptStore receipts, SourceRawPublicationRedriveProperties properties,
                                      ConfigurableApplicationContext context) {
        this.receipts = receipts;
        this.properties = properties;
        this.context = context;
    }

    @Override public void run(ApplicationArguments args) {
        if (!"none".equalsIgnoreCase(context.getEnvironment().getProperty("spring.main.web-application-type", ""))) {
            throw new IllegalStateException("SOURCE_RAW_REDRIVE_REQUIRES_PROTECTED_NON_WEB_PROCESS");
        }
        SourceReceiptStore.RedriveReason reason = SourceReceiptStore.RedriveReason.valueOf(properties.reason());
        SourceReceiptStore.RedriveResult result = receipts.redriveQuarantined(properties.receiptId(), reason);
        if (result.status() != SourceReceiptStore.RedriveStatus.REQUEUED) {
            throw new IllegalStateException("SOURCE_RAW_REDRIVE_" + result.status());
        }
        log.warn("Authorized source RAW publication redrive queued. reason={} recoveryEpoch={}", reason, result.recoveryEpoch());
        context.close();
    }
}
