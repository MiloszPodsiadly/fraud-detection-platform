package com.frauddetection.ingest.source;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.source.*;
import com.mongodb.client.MongoClient;
import java.time.Clock;
import java.time.Duration;

/** Test-only bridge to the real receipt owner; creates no fabricated receipt/lineage documents. */
public final class DeviceTestSourceReceipts {
    private DeviceTestSourceReceipts() { }
    public static void admit(MongoClient client, String database, Clock clock, SourceSignatureVerifier policy,
                             MongoSourceLineage lineage, SourceSignatureVerifier.Verified evidence, TransactionRawEvent raw) {
        var receipts = new MongoSourceReceiptStore(client, database, clock,
                new SourceReceiptStore.Limits(Duration.ofDays(30), Duration.ofDays(45), 1_000), policy);
        var result = receipts.admit(evidence);
        if (result.status() != SourceReceiptStore.Status.ADMITTED && result.status() != SourceReceiptStore.Status.EXISTING) {
            throw new IllegalStateException("TEST_SOURCE_ADMISSION_FAILED");
        }
        if (lineage.bindRaw(evidence, result.receipt().id(), raw).state() != SourceLineage.State.VERIFIED) {
            throw new IllegalStateException("TEST_SOURCE_RAW_BINDING_FAILED");
        }
    }
}
