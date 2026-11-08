package com.frauddetection.ingest.source;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("fraud.source-receipts")
public record SourceReceiptProperties(boolean enabled, String mongoUri, String database,
                                      Duration replayHorizon, Duration receiptRetention, int maxReceipts) {
    public SourceReceiptProperties {
        if (enabled) {
            if (mongoUri == null || mongoUri.isBlank() || database == null || !database.matches("[a-z][a-z0-9_]{0,63}")) {
                throw new IllegalArgumentException("SOURCE_RECEIPT_CONFIGURATION_REQUIRED");
            }
            new SourceReceiptStore.Limits(replayHorizon, receiptRetention, maxReceipts);
        }
    }
    @Override public String toString() { return "SourceReceiptProperties[redacted]"; }
}
