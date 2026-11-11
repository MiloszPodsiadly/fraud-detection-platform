package com.frauddetection.common.events.source;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Private persistence protocol. A commitment locates facts; it does not authenticate them. */
public final class SourceReceiptIdentity {
    private SourceReceiptIdentity() { }
    public static String identity(SourceScope scope) {
        return digest("source-receipt-identity/v1", scope.issuer(), scope.institution(), scope.source(),
                scope.sourceEpoch(), scope.sourceEventId());
    }
    public static String binding(SourceScope scope, String keyCommitment) {
        return digest("source-receipt-binding/v1", identity(scope), scope.signingKeyVersion(), scope.eventTime().toString(),
                scope.purpose(), scope.audience(), scope.payloadCommitment(), keyCommitment);
    }
    static String digest(String... fields) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); hash.update(bytes);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SOURCE_COMMITMENT_UNAVAILABLE"); }
    }
}
