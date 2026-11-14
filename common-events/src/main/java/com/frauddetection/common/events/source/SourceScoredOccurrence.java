package com.frauddetection.common.events.source;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/** Private exact fact reference. Source authenticity and the scored fact fingerprint are separate evidence. */
@JsonIgnoreType
public final class SourceScoredOccurrence {
    private final SourceScope source;
    private final String receiptId;
    private final String enrichedCommitment;
    private final String scoredEventId;
    private final Instant scoredCreatedAt;
    private final String scoredFingerprint;
    private final String runId;
    SourceScoredOccurrence(SourceScope source, String receiptId, String enrichedCommitment,
                           String scoredEventId, Instant scoredCreatedAt, String scoredFingerprint, String runId) {
        this.source = source; this.receiptId = receiptId; this.enrichedCommitment = enrichedCommitment;
        this.scoredEventId = scoredEventId; this.scoredCreatedAt = scoredCreatedAt; this.scoredFingerprint = scoredFingerprint;
        this.runId = runId;
    }
    public SourceScope source() { return source; }
    public String receiptId() { return receiptId; }
    public String enrichedCommitment() { return enrichedCommitment; }
    public String scoredEventId() { return scoredEventId; }
    public Instant scoredCreatedAt() { return scoredCreatedAt; }
    public String scoredFingerprint() { return scoredFingerprint; }
    public String runId() { return runId; }
    private static final JsonMapper CANONICAL = JsonMapper.builder().findAndAddModules()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    public static String fingerprint(TransactionScoredEvent event) {
        try {
            if (event == null) { throw new IllegalArgumentException(); }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(CANONICAL, event)));
        } catch (Exception invalid) { throw new IllegalArgumentException("SOURCE_SCORED_FINGERPRINT_UNAVAILABLE"); }
    }
    static byte[] bytes(JsonMapper mapper, Object value) {
        var output = new java.io.ByteArrayOutputStream() {
            @Override public synchronized void write(int value) {
                if (count == 262_144) { throw new IllegalArgumentException("SOURCE_SCORED_PAYLOAD_TOO_LARGE"); }
                super.write(value);
            }
            @Override public synchronized void write(byte[] data, int offset, int length) {
                if (length > 262_144 - count) { throw new IllegalArgumentException("SOURCE_SCORED_PAYLOAD_TOO_LARGE"); }
                super.write(data, offset, length);
            }
        };
        mapper.writeValue(output, value);
        return output.toByteArray();
    }
    @Override public String toString() { return "SourceScoredOccurrence[redacted]"; }
}
