package com.frauddetection.enricher.device;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.util.HexFormat;
import java.util.Locale;

/** Dedicated in-memory pseudonym key; never an issuer key or a source of Device authority. */
@JsonIgnoreType
final class DevicePseudonyms {
    private final byte[] key;
    private final int version;
    private final ThreadLocal<MacState> macStates;
    DevicePseudonyms(byte[] key, int version) {
        if (key == null || key.length < 32 || key.length > 64 || version < 1 || version > 65_535) { throw new IllegalArgumentException("DEVICE_PSEUDONYM_KEY_INVALID"); }
        this.key = key.clone(); this.version = version;
        this.macStates = ThreadLocal.withInitial(() -> new MacState(this.key));
    }
    int version() { return version; }
    String institution(String institution) { return token("institution", institution); }
    String customer(String institution, String customer) { return token("customer", institution, customer); }
    String device(String institution, String device) { return token("device", institution, device); }
    String transaction(String institution, String customer, String transaction) { return token("transaction", institution, customer, transaction); }
    String event(String institution, String event) { return token("source-event", institution, event); }
    /** Domain-separated server MAC for persisted verified facts; this grants no Device authority. */
    String integrity(String recordType, String... fields) {
        if (!validRecordType(recordType)) {
            throw new IllegalArgumentException("DEVICE_RECORD_INTEGRITY_UNAVAILABLE");
        }
        return token("persisted-record-" + recordType, fields);
    }
    static String sourceCode(String source) {
        DeviceAssertions.reference(source);
        return "S_" + new java.math.BigInteger(DeviceEpoch.digest("device-source-code/v1", source), 16).toString(36).toUpperCase(Locale.ROOT);
    }
    private String token(String kind, String... fields) {
        try {
            MacState state = macStates.get();
            state.mac.reset();
            writeField(state, "device-risk/" + kind + "/v1");
            for (String field : fields) { writeField(state, field); }
            return HexFormat.of().formatHex(state.mac.doFinal());
        } catch (Exception invalid) { throw new IllegalArgumentException("DEVICE_PSEUDONYM_MAPPING_UNAVAILABLE"); }
    }

    private static boolean validRecordType(String value) {
        if (value == null || value.isEmpty() || value.length() > 64 || value.charAt(0) < 'a' || value.charAt(0) > 'z') { return false; }
        for (int index = 1; index < value.length(); index++) {
            char current = value.charAt(index);
            if ((current < 'a' || current > 'z') && (current < '0' || current > '9') && current != '-') { return false; }
        }
        return true;
    }

    private static void writeField(MacState state, String value) throws java.nio.charset.CharacterCodingException {
        if (value == null || value.isBlank() || value.length() > 512) { throw new IllegalArgumentException(); }
        CharsetEncoder encoder = state.encoder.reset();
        ByteBuffer encoded = state.encoded;
        encoded.clear();
        var result = encoder.encode(CharBuffer.wrap(value), encoded, true);
        if (result.isError()) { result.throwException(); }
        if (result.isOverflow()) { throw new IllegalArgumentException(); }
        result = encoder.flush(encoded);
        if (result.isError()) { result.throwException(); }
        if (result.isOverflow()) { throw new IllegalArgumentException(); }
        int length = encoded.position();
        state.mac.update((byte) (length >>> 24));
        state.mac.update((byte) (length >>> 16));
        state.mac.update((byte) (length >>> 8));
        state.mac.update((byte) length);
        state.mac.update(encoded.array(), 0, length);
    }

    private static final class MacState {
        private final Mac mac;
        private final CharsetEncoder encoder;
        private final ByteBuffer encoded = ByteBuffer.allocate(1_536);

        private MacState(byte[] key) {
            try {
                mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(key, "HmacSHA256"));
                encoder = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
            } catch (Exception unavailable) { throw new IllegalStateException("DEVICE_PSEUDONYM_MAPPING_UNAVAILABLE"); }
        }
    }

    @Override public String toString() { return "DevicePseudonyms[redacted]"; }
}
