package com.frauddetection.enricher.graph;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Strict, bounded, ordered, length-delimited keys for internal graph identities. */
final class GraphCanonical {
    private static final String ROOT_DOMAIN = "fraud-detection/graph-evidence/v1";
    private static final int MAX_FIELDS = 32;
    private static final int MAX_FIELD_CHARS = 1_024;
    private static final int MAX_FIELD_BYTES = 4_096;
    private static final int MAX_TOTAL_BYTES = 16_384;

    private GraphCanonical() { }

    static String digest(String domain, String... fields) {
        if (domain == null || domain.isBlank() || fields == null || fields.length > MAX_FIELDS - 2) {
            throw new IllegalArgumentException("GRAPH_CANONICAL_INPUT_INVALID");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int total = 0;
            total = update(digest, ROOT_DOMAIN, total);
            total = update(digest, domain, total);
            for (String field : fields) {
                total = update(digest, field, total);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("GRAPH_CANONICAL_HASH_UNAVAILABLE");
        }
    }

    private static int update(MessageDigest digest, String value, int total) {
        if (value == null || value.length() > MAX_FIELD_CHARS) {
            throw new IllegalArgumentException("GRAPH_CANONICAL_INPUT_INVALID");
        }
        byte[] bytes = strictUtf8(value);
        if (bytes.length > MAX_FIELD_BYTES || total > MAX_TOTAL_BYTES - Integer.BYTES - bytes.length) {
            throw new IllegalArgumentException("GRAPH_CANONICAL_INPUT_TOO_LARGE");
        }
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
        return total + Integer.BYTES + bytes.length;
    }

    private static byte[] strictUtf8(String value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            return result;
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("GRAPH_CANONICAL_ENCODING_INVALID");
        }
    }
}
