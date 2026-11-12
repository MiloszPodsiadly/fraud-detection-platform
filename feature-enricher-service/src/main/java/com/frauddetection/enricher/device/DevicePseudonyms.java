package com.frauddetection.enricher.device;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.CharBuffer;
import java.util.HexFormat;
import java.util.Locale;

/** Dedicated in-memory pseudonym key; never an issuer key or a source of Device authority. */
@JsonIgnoreType
final class DevicePseudonyms {
    private final byte[] key;
    private final int version;
    DevicePseudonyms(byte[] key, int version) {
        if (key == null || key.length < 32 || key.length > 64 || version < 1 || version > 65_535) { throw new IllegalArgumentException("DEVICE_PSEUDONYM_KEY_INVALID"); }
        this.key = key.clone(); this.version = version;
    }
    int version() { return version; }
    String institution(String institution) { return token("institution", institution); }
    String customer(String institution, String customer) { return token("customer", institution, customer); }
    String device(String institution, String device) { return token("device", institution, device); }
    String transaction(String institution, String customer, String transaction) { return token("transaction", institution, customer, transaction); }
    String event(String institution, String event) { return token("source-event", institution, event); }
    /** Domain-separated server MAC for persisted verified facts; this grants no Device authority. */
    String integrity(String recordType, String... fields) {
        if (recordType == null || !recordType.matches("[a-z][a-z0-9-]{0,63}")) {
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
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes);
            for (String value : java.util.stream.Stream.concat(java.util.stream.Stream.of("device-risk/" + kind + "/v1"), java.util.Arrays.stream(fields)).toList()) {
                if (value == null || value.isBlank() || value.length() > 512) { throw new IllegalArgumentException(); }
                var buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
                byte[] encoded = new byte[buffer.remaining()]; buffer.get(encoded);
                output.writeInt(encoded.length); output.write(encoded);
            }
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(bytes.toByteArray()));
        } catch (Exception invalid) { throw new IllegalArgumentException("DEVICE_PSEUDONYM_MAPPING_UNAVAILABLE"); }
    }
    @Override public String toString() { return "DevicePseudonyms[redacted]"; }
}
