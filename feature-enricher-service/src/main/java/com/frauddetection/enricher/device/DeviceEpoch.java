package com.frauddetection.enricher.device;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.setOnInsert;

/** Explicit persisted source-origin epoch. No legacy/default epoch can be inferred. */
@JsonIgnoreType
record DeviceEpoch(String sourceIssuer, String sourceEpoch) {
    static final int VERSION = 2;
    private static final ThreadLocal<DigestState> DIGEST_STATE = ThreadLocal.withInitial(() -> {
        try { return new DigestState(MessageDigest.getInstance("SHA-256")); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("DEVICE_COMMITMENT_UNAVAILABLE"); }
    });
    DeviceEpoch { DeviceAssertions.reference(sourceIssuer); DeviceAssertions.reference(sourceEpoch); }
    String scopeId(String institution, String source) {
        return digest("device-scope/v2", sourceIssuer, institution, source, sourceEpoch, Integer.toString(VERSION));
    }
    String eventId(String institution, String source, String event) { return digest("device-event/v2", scopeId(institution, source), event); }
    Bson filter(String institution, String source) {
        return and(eq("schemaVersion", VERSION), eq("identityVersion", VERSION), eq("sourceIssuer", sourceIssuer),
                eq("sourceEpoch", sourceEpoch), eq("institution", institution), eq("source", source));
    }
    Document document(String id, String institution, String source) {
        return new Document("_id", id).append("schemaVersion", VERSION).append("identityVersion", VERSION)
                .append("sourceIssuer", sourceIssuer).append("sourceEpoch", sourceEpoch)
                .append("institution", institution).append("source", source);
    }
    Bson onInsert(String institution, String source) {
        return combine(setOnInsert("schemaVersion", VERSION), setOnInsert("identityVersion", VERSION),
                setOnInsert("sourceIssuer", sourceIssuer), setOnInsert("sourceEpoch", sourceEpoch),
                setOnInsert("institution", institution), setOnInsert("source", source));
    }
    boolean matches(Document row) {
        return row != null && Integer.valueOf(VERSION).equals(row.getInteger("schemaVersion"))
                && Integer.valueOf(VERSION).equals(row.getInteger("identityVersion"))
                && sourceIssuer.equals(row.getString("sourceIssuer")) && sourceEpoch.equals(row.getString("sourceEpoch"));
    }
    static String digest(String domain, String... fields) {
        try {
            DigestState state = DIGEST_STATE.get();
            MessageDigest digest = state.digest;
            digest.reset();
            update(state, domain);
            for (String field : fields) { update(state, field); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception unavailable) { throw new IllegalStateException("DEVICE_COMMITMENT_UNAVAILABLE"); }
    }
    private static void update(DigestState state, String value) throws java.nio.charset.CharacterCodingException {
        CharsetEncoder encoder = state.encoder.reset();
        ByteBuffer encoded = state.encoded;
        int required = value.length() > (Integer.MAX_VALUE - 8) / 3 ? Integer.MAX_VALUE - 8 : value.length() * 3;
        if (required > encoded.capacity()) {
            encoded = ByteBuffer.allocate(required);
            state.encoded = encoded;
        }
        encoded.clear();
        var result = encoder.encode(CharBuffer.wrap(value), encoded, true);
        if (result.isError()) { result.throwException(); }
        if (result.isOverflow()) { throw new IllegalStateException("DEVICE_COMMITMENT_UNAVAILABLE"); }
        result = encoder.flush(encoded);
        if (result.isError()) { result.throwException(); }
        if (result.isOverflow()) { throw new IllegalStateException("DEVICE_COMMITMENT_UNAVAILABLE"); }
        int length = encoded.position();
        MessageDigest digest = state.digest;
        digest.update((byte) (length >>> 24));
        digest.update((byte) (length >>> 16));
        digest.update((byte) (length >>> 8));
        digest.update((byte) length);
        digest.update(encoded.array(), 0, length);
    }

    private static final class DigestState {
        private final MessageDigest digest;
        private final CharsetEncoder encoder;
        private ByteBuffer encoded = ByteBuffer.allocate(256);

        private DigestState(MessageDigest digest) {
            this.digest = digest;
            this.encoder = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .replaceWith(new byte[]{(byte) '?'});
        }
    }
    @Override public String toString() { return "DeviceEpoch[redacted]"; }
}
