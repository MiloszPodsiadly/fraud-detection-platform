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
