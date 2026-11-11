package com.frauddetection.common.events.source;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** V1: big-endian uint32 lengths, UTF-8 strings in the order below, then exact payload bytes.
 * JSON envelope formatting is not signed; every parsed claim and the original bytes are signed.
 * No JSON canonicalization or reserialization of the original payload is performed.
 */
public final class SourceSignedInput {
    public static final int VERSION = 1;
    public static final String ALGORITHM = "Ed25519";
    public static final int MAX_PAYLOAD_BYTES = 65_536;
    public static final int MAX_PROOF_BYTES = 4_096;

    private SourceSignedInput() { }

    public static byte[] encode(SourceScope scope, byte[] payload) {
        requirePayload(payload);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(buffer);
            field(output, "fraud-source-attestation/v1".getBytes(StandardCharsets.US_ASCII));
            output.writeInt(VERSION);
            for (String value : new String[] {ALGORITHM, scope.issuer(), scope.institution(), scope.source(),
                    scope.sourceEpoch(), scope.signingKeyVersion(), scope.sourceEventId(),
                    scope.eventTime().toString(), scope.purpose(), scope.audience(), scope.payloadCommitment()}) {
                field(output, value.getBytes(StandardCharsets.UTF_8));
            }
            field(output, payload);
            return buffer.toByteArray();
        } catch (IOException impossibleForMemoryOutput) {
            throw new IllegalStateException("SOURCE_SIGNED_INPUT_UNAVAILABLE");
        }
    }

    public static String payloadCommitment(byte[] payload) {
        requirePayload(payload);
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update("fraud-source-payload/v1\0".getBytes(StandardCharsets.US_ASCII));
            hash.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(payload.length).array());
            return HexFormat.of().formatHex(hash.digest(payload));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SOURCE_PAYLOAD_HASH_UNAVAILABLE");
        }
    }

    private static void field(DataOutputStream output, byte[] bytes) throws IOException {
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void requirePayload(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("SOURCE_PAYLOAD_SIZE_INVALID");
        }
    }
}
