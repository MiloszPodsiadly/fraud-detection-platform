package com.frauddetection.alert.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** A deterministic, non-authenticating precondition token for one authoritative scoring occurrence. */
public final class ScoringOccurrenceToken {

    private static final byte[] DOMAIN = "fraud-scoring-occurrence-token-v1".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private ScoringOccurrenceToken() {
    }

    public static String from(ScoringOccurrenceOwnership ownership) {
        if (ownership == null) {
            throw new IllegalArgumentException("AUTHORITATIVE_SCORING_OCCURRENCE_IDENTITY_REQUIRED");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DOMAIN);
            updateLengthPrefixed(digest, ownership.sourceEventId().getBytes(StandardCharsets.UTF_8));
            updateLengthPrefixed(digest, ownership.sourceEventCreatedAt().toString().getBytes(StandardCharsets.US_ASCII));
            digest.update(HexFormat.of().parseHex(ownership.sourceEventFingerprint()));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SCORING_OCCURRENCE_TOKEN_HASH_UNAVAILABLE", exception);
        }
    }

    public static boolean isValid(String value) {
        return value != null && TOKEN_PATTERN.matcher(value).matches();
    }

    private static void updateLengthPrefixed(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }
}
