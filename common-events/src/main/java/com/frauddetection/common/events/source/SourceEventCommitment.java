package com.frauddetection.common.events.source;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionRawEvent;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamWriteConstraints;
import tools.jackson.core.json.JsonFactory;

import java.nio.charset.StandardCharsets;

/** V1 canonical commitment covers every interpreted raw/enriched contract field, including receiver ID/time. */
public final class SourceEventCommitment {
    private static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder().streamWriteConstraints(
                    StreamWriteConstraints.builder().maxNestingDepth(64).build()).build()).findAndAddModules()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    public static final int MAX_EVENT_BYTES = 262_144;
    private SourceEventCommitment() { }
    public static String raw(TransactionRawEvent event) { return commit("raw", event); }
    public static String enriched(TransactionEnrichedEvent event) { return commit("enriched", event); }
    /** Stable private identity for the Redis history effect; receipt identifiers are never written to Redis. */
    public static String attestedHistory(String receiptId, TransactionEnrichedEvent event) {
        if (receiptId == null || !receiptId.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("SOURCE_RECEIPT_ID_INVALID");
        }
        return SourceReceiptIdentity.digest("source-enriched-history/v1", receiptId, enriched(event));
    }
    /** Stable private identity for enforcing one business transaction per verified source scope. */
    public static String businessTransaction(SourceScope scope, String customerId, String transactionId) {
        if (scope == null || customerId == null || customerId.isBlank() || customerId.length() > 512
                || transactionId == null || transactionId.isBlank() || transactionId.length() > 512) {
            throw new IllegalArgumentException("SOURCE_BUSINESS_IDENTITY_INVALID");
        }
        String identity = SourceReceiptIdentity.digest("source-business-transaction-identity/v1", scope.issuer(),
                scope.institution(), scope.source(), scope.sourceEpoch(), customerId, transactionId);
        if (identity.equals("0".repeat(64))) { throw new IllegalArgumentException("SOURCE_BUSINESS_IDENTITY_INVALID"); }
        return identity;
    }
    static String eventIdentity(String kind, String eventId, java.time.Instant time) {
        SourceScope.requireReference(eventId);
        if (time == null) { throw new IllegalArgumentException("SOURCE_EVENT_TIME_REQUIRED"); }
        return SourceReceiptIdentity.digest("source-lineage-event-identity/v1", kind, eventId, time.toString());
    }
    private static String commit(String kind, Object event) {
        if (event == null) { throw new IllegalArgumentException("SOURCE_EVENT_REQUIRED"); }
        try {
            BoundedBuffer output = new BoundedBuffer();
            MAPPER.writeValue(output, event);
            byte[] bytes = output.toByteArray();
            return SourceReceiptIdentity.digest("source-lineage-event-content/v1", kind, new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("SOURCE_EVENT_COMMITMENT_INVALID"); }
    }
    private static final class BoundedBuffer extends java.io.ByteArrayOutputStream {
        @Override public synchronized void write(int value) {
            if (count == MAX_EVENT_BYTES) { throw new IllegalArgumentException("SOURCE_EVENT_TOO_LARGE"); }
            super.write(value);
        }
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            if (length > MAX_EVENT_BYTES - count) { throw new IllegalArgumentException("SOURCE_EVENT_TOO_LARGE"); }
            super.write(bytes, offset, length);
        }
    }
}
