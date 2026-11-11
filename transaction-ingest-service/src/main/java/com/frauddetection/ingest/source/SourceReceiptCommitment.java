package com.frauddetection.ingest.source;

import com.frauddetection.common.events.source.SourceScope;
import com.frauddetection.common.events.source.SourceReceiptIdentity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class SourceReceiptCommitment {
    private SourceReceiptCommitment() { }

    // Key version deliberately does not split source identity: re-signing the same event cannot bypass a conflict.
    static String identity(SourceScope scope) {
        return SourceReceiptIdentity.identity(scope);
    }

    static String binding(SourceScope scope, String keyCommitment) {
        return SourceReceiptIdentity.binding(scope, keyCommitment);
    }

    static String limits(SourceReceiptStore.Limits limits) {
        return digest("source-receipt-limits/v1", limits.replayHorizon().toString(), limits.receiptRetention().toString(),
                Integer.toString(limits.maxReceipts()));
    }

    private static String digest(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SOURCE_RECEIPT_DIGEST_UNAVAILABLE");
        }
    }
}
