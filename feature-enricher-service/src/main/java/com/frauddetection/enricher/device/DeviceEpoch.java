package com.frauddetection.enricher.device;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.setOnInsert;

/** Explicit persisted source-origin epoch. No legacy/default epoch can be inferred. */
@JsonIgnoreType
record DeviceEpoch(String sourceIssuer, String sourceEpoch) {
    static final int VERSION = 2;
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
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            for (String field : java.util.stream.Stream.concat(java.util.stream.Stream.of(domain), java.util.Arrays.stream(fields)).toList()) {
                byte[] encoded = field.getBytes(StandardCharsets.UTF_8); out.writeInt(encoded.length); out.write(encoded);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (Exception unavailable) { throw new IllegalStateException("DEVICE_COMMITMENT_UNAVAILABLE"); }
    }
    @Override public String toString() { return "DeviceEpoch[redacted]"; }
}
