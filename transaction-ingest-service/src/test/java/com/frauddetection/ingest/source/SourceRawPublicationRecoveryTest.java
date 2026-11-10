package com.frauddetection.ingest.source;

import com.frauddetection.ingest.messaging.TransactionRawEventProducer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SourceRawPublicationRecoveryTest {
    private static final SourceRawPublicationProperties PROPERTIES = new SourceRawPublicationProperties(
            true, "raw-key-1", Map.of("raw-key-1", "test-key"), Duration.ofSeconds(1), Duration.ofSeconds(30), 10, 8);

    @Test
    void compactionFailureDoesNotSuppressRawPublicationRecovery() {
        SourceReceiptStore receipts = mock(SourceReceiptStore.class);
        TransactionRawEventProducer publisher = mock(TransactionRawEventProducer.class);
        when(receipts.recoverRawPublications(publisher, PROPERTIES.batchSize())).thenReturn(0);
        doThrow(new IllegalStateException("simulated compaction outage")).when(receipts).compactExpired(50);

        new SourceRawPublicationRecovery(receipts, publisher, PROPERTIES).recover();

        verify(receipts).recoverRawPublications(publisher, PROPERTIES.batchSize());
        verify(receipts).compactExpired(50);
    }

    @Test
    void publicationRecoveryFailureDoesNotSuppressExpiredCompaction() {
        SourceReceiptStore receipts = mock(SourceReceiptStore.class);
        TransactionRawEventProducer publisher = mock(TransactionRawEventProducer.class);
        doThrow(new IllegalStateException("simulated broker outage"))
                .when(receipts).recoverRawPublications(publisher, PROPERTIES.batchSize());
        when(receipts.compactExpired(50)).thenReturn(1);

        new SourceRawPublicationRecovery(receipts, publisher, PROPERTIES).recover();

        verify(receipts).recoverRawPublications(publisher, PROPERTIES.batchSize());
        verify(receipts).compactExpired(50);
    }
}
