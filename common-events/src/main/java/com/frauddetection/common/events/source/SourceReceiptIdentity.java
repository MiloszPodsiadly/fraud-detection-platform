package com.frauddetection.common.events.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Private persistence protocol. A commitment locates facts; it does not authenticate them. */
public final class SourceReceiptIdentity {
    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(() -> {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SOURCE_COMMITMENT_UNAVAILABLE"); }
    });
    private SourceReceiptIdentity() { }
    public static String identity(SourceScope scope) {
        return digest("source-receipt-identity/v1", scope.issuer(), scope.institution(), scope.source(),
                scope.sourceEpoch(), scope.sourceEventId());
    }
    public static String binding(SourceScope scope, String keyCommitment) {
        return binding(identity(scope), scope.signingKeyVersion(), scope.eventTime(), scope.purpose(), scope.audience(),
                scope.payloadCommitment(), keyCommitment);
    }
    static String binding(String identity, String signingKeyVersion, java.time.Instant eventTime, String purpose,
                          String audience, String payloadCommitment, String keyCommitment) {
        return digest("source-receipt-binding/v1", identity, signingKeyVersion, eventTime.toString(), purpose, audience,
                payloadCommitment, keyCommitment);
    }
    static String digest(String... fields) {
        MessageDigest hash = SHA_256.get();
        hash.reset();
        for (String field : fields) {
            byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            int length = bytes.length;
            hash.update((byte) (length >>> 24));
            hash.update((byte) (length >>> 16));
            hash.update((byte) (length >>> 8));
            hash.update((byte) length);
            hash.update(bytes);
        }
        return HexFormat.of().formatHex(hash.digest());
    }
}
