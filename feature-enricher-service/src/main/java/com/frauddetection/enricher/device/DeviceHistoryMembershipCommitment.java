package com.frauddetection.enricher.device;

import org.bson.Document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Private Stage A anchor for the exact retained history member set used to derive its bounded facts. */
final class DeviceHistoryMembershipCommitment {
    private DeviceHistoryMembershipCommitment() { }

    static Value compute(DevicePseudonyms key, DeviceEpoch epoch, String institution, String source,
                         String customer, String device, int keyVersion, Instant windowStart, Instant asOf,
                         Collection<Document> rows) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(epoch, "epoch");
        TreeMap<String, String> members = new TreeMap<>();
        for (Document row : rows) {
            String id = required(row, "_id");
            Date eventTime = Objects.requireNonNull(row.getDate("eventTime"), "eventTime");
            Date expireAt = Objects.requireNonNull(row.getDate("expireAt"), "expireAt");
            String leaf = DeviceEpoch.digest("device-history-member/v1", id,
                    required(row, "sourceIssuer"), required(row, "identityVersion"), required(row, "sourceEpoch"),
                    required(row, "institution"), required(row, "source"), required(row, "sourceEvent"),
                    required(row, "customer"), required(row, "device"), required(row, "keyVersion"),
                    eventTime.toInstant().toString(), expireAt.toInstant().toString(),
                    required(row, "provenanceCommitment"), optional(row.getString("membershipIntegrityCommitment")));
            String previous = members.putIfAbsent(id, leaf);
            if (previous != null && !previous.equals(leaf)) {
                throw new IllegalStateException("DEVICE_HISTORY_MEMBERSHIP_CONFLICT");
            }
        }

        String digest = leafSetDigest(members);
        String commitment = key.integrity("history-membership", epoch.scopeId(institution, source),
                institution, source, customer, device, Integer.toString(keyVersion), windowStart.toString(),
                asOf.toString(), Integer.toString(members.size()), digest);
        return new Value(commitment, members.size());
    }

    private static String leafSetDigest(Map<String, String> members) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            members.forEach((id, leaf) -> {
                digest.update(id.getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(leaf.getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) '\n');
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("DEVICE_HISTORY_DIGEST_UNAVAILABLE", unavailable);
        }
    }

    private static String required(Document row, String field) {
        Object value = row.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException("DEVICE_HISTORY_MEMBER_INVALID");
        }
        return value.toString();
    }

    private static String optional(String value) { return value == null ? "NONE" : value; }

    record Value(String commitment, int size) { }
}
