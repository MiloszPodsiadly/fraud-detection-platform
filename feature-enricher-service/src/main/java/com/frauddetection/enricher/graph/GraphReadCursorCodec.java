package com.frauddetection.enricher.graph;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;

/** Authenticated keyset cursor. Claims contain only opaque tokens and bounded query metadata. */
final class GraphReadCursorCodec {
    private static final int VERSION = 1;
    private static final int MAX_TOKEN_LENGTH = 8_192;
    private final byte[] signingKey;

    GraphReadCursorCodec(byte[] signingKey) {
        Objects.requireNonNull(signingKey, "GRAPH_CURSOR_KEY_REQUIRED");
        if (signingKey.length < 32) { throw new IllegalArgumentException("GRAPH_CURSOR_KEY_INVALID"); }
        this.signingKey = signingKey.clone();
    }

    String encode(Claims claims) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(VERSION);
                output.writeUTF(claims.scopeHash());
                output.writeUTF(claims.edgeType());
                output.writeUTF(claims.rootId());
                output.writeLong(claims.fromMillis());
                output.writeLong(claims.untilMillis());
                output.writeLong(claims.asOfMillis());
                output.writeInt(claims.policyVersion());
                output.writeUTF(claims.lastEdgeId());
                output.writeUTF(claims.datasetHash());
            }
            byte[] payload = bytes.toByteArray();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(payload));
        } catch (Exception failure) {
            throw new IllegalStateException("GRAPH_CURSOR_ENCODING_FAILED");
        }
    }

    Claims decode(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_TOKEN_LENGTH) { throw invalid(); }
        try {
            String[] parts = encoded.split("\\.", -1);
            if (parts.length != 2) { throw invalid(); }
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            if (!MessageDigest.isEqual(mac(payload), signature)) { throw invalid(); }
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
                if (input.readInt() != VERSION) { throw invalid(); }
                Claims claims = new Claims(input.readUTF(), input.readUTF(), input.readUTF(), input.readLong(),
                        input.readLong(), input.readLong(), input.readInt(), input.readUTF(), input.readUTF());
                if (input.available() != 0) { throw invalid(); }
                return claims;
            }
        } catch (RuntimeException | java.io.IOException failure) {
            throw invalid();
        }
    }

    private byte[] mac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (Exception failure) {
            throw new IllegalStateException("GRAPH_CURSOR_SIGNING_FAILED");
        }
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("GRAPH_CURSOR_INVALID"); }

    record Claims(String scopeHash, String edgeType, String rootId, long fromMillis, long untilMillis, long asOfMillis,
                  int policyVersion, String lastEdgeId, String datasetHash) {
        Claims {
            GraphValidation.token(scopeHash);
            if (!GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE.name().equals(edgeType)) { throw invalid(); }
            GraphValidation.token(rootId);
            GraphValidation.token(lastEdgeId);
            GraphValidation.token(datasetHash);
            GraphValidation.version(policyVersion);
            if (untilMillis <= fromMillis || asOfMillis < 0) { throw invalid(); }
        }
    }
}
