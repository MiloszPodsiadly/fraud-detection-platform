package com.frauddetection.enricher.merchant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Shared length-delimited SHA-256 encoding for merchant-store and profile commitments. */
final class MerchantCommitment {
    private MerchantCommitment() { }

    static String sha256(String unavailableCode, Object... values) {
        return accumulator(unavailableCode).append(values).finish();
    }

    static Accumulator accumulator(String unavailableCode) {
        try { return new Accumulator(MessageDigest.getInstance("SHA-256")); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(unavailableCode); }
    }

    static final class Accumulator {
        private final MessageDigest digest;
        private boolean finished;

        private Accumulator(MessageDigest digest) { this.digest = digest; }

        Accumulator append(Object... values) {
            if (finished) { throw new IllegalStateException("MERCHANT_COMMITMENT_FINISHED"); }
            for (Object value : values) {
                String text = value == null ? null : value.toString();
                byte[] bytes = text == null ? null : text.getBytes(StandardCharsets.UTF_8);
                byte[] length = ((bytes == null ? -1 : bytes.length) + ":").getBytes(StandardCharsets.UTF_8);
                digest.update(length);
                if (bytes != null) { digest.update(bytes); }
            }
            return this;
        }

        String finish() {
            if (finished) { throw new IllegalStateException("MERCHANT_COMMITMENT_FINISHED"); }
            finished = true;
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
