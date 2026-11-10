package com.frauddetection.ingest.source;

import com.frauddetection.common.events.source.SourceScope;
import com.frauddetection.common.events.source.SourceSignatureVerifier;
import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.ingest.messaging.TransactionRawEventProducer;


import java.time.Duration;
import java.time.Instant;

/** Private source-authority seam. A receipt grants neither domain history access nor a scoring-occurrence binding. */
interface SourceReceiptStore {
    Result admit(SourceSignatureVerifier.Verified evidence);
    RawResult acceptRaw(SourceSignatureVerifier.Verified evidence, TransactionRawEvent candidate);
    int recoverRawPublications(TransactionRawEventProducer publisher, int limit);
    ReadOnly readOnly();
    int compactExpired(int limit);

    @FunctionalInterface
    interface ReadOnly {
        Result lookup(SourceSignatureVerifier.Verified authorization, String receiptId);
    }

    enum Status { ADMITTED, EXISTING, UNVERIFIED, CONFLICT, REVOKED, UNRESOLVED, TOMBSTONED, REPLAY_EXPIRED, CAPACITY_EXCEEDED }

    record Result(Status status, Receipt receipt) {
        public Result {
            if (status == null || ((status == Status.ADMITTED || status == Status.EXISTING) != (receipt != null))) {
                throw new IllegalArgumentException("SOURCE_RECEIPT_RESULT_INVALID");
            }
        }
        @Override public String toString() { return "SourceReceiptResult[" + status + "]"; }
    }

    record RawResult(Status status, Receipt receipt, TransactionRawEvent event) {
        public RawResult {
            boolean accepted = status == Status.ADMITTED || status == Status.EXISTING;
            if (status == null || accepted && (receipt == null || event == null)
                    || !accepted && (receipt != null || event != null)) {
                throw new IllegalArgumentException("SOURCE_RAW_RESULT_INVALID");
            }
        }
        @Override public String toString() { return "SourceRawResult[" + status + "]"; }
    }

    /** Data projection, not an authority capability. Original payload/signature/key bytes are never stored here. */
    record Receipt(String id, SourceScope scope, String keyCommitment, String policyVersion,
                   Instant verifiedAt, Instant admittedAt, Instant expiresAt) {
        @Override public String toString() { return "SourceReceipt[redacted]"; }
    }

    /** Tombstones consume capacity permanently; no TTL deletion may erase the replay fence. */
    record Limits(Duration replayHorizon, Duration receiptRetention, int maxReceipts) {
        public Limits {
            if (replayHorizon == null || receiptRetention == null || replayHorizon.isZero() || replayHorizon.isNegative()
                    || replayHorizon.compareTo(Duration.ofDays(30)) > 0
                    || receiptRetention.compareTo(replayHorizon.plusMinutes(5)) < 0
                    || receiptRetention.compareTo(Duration.ofDays(90)) > 0
                    || replayHorizon.getNano() % 1_000_000 != 0 || receiptRetention.getNano() % 1_000_000 != 0
                    || maxReceipts < 1 || maxReceipts > 100_000) {
                throw new IllegalArgumentException("SOURCE_RECEIPT_LIMITS_INVALID");
            }
        }
    }
}
