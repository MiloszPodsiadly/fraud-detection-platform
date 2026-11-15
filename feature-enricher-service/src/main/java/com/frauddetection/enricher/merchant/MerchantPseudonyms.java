package com.frauddetection.enricher.merchant;

import com.frauddetection.common.events.source.SourceScope;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** Private server-side mapping. Its key is separate from both issuer signing roles. */
final class MerchantPseudonyms {
    private final byte[] key;
    private final int identityVersion;
    private final int mappingVersion;
    private final String mappingCommitment;

    MerchantPseudonyms(byte[] key, int identityVersion, int mappingVersion) {
        if (key == null || key.length < 32 || key.length > 64 || identityVersion < 1 || identityVersion > 65_535
                || mappingVersion < 1 || mappingVersion > 65_535) {
            throw new IllegalArgumentException("MERCHANT_PSEUDONYM_MAPPING_INVALID");
        }
        this.key = key.clone();
        this.identityVersion = identityVersion;
        this.mappingVersion = mappingVersion;
        this.mappingCommitment = mappingCommitment(key, identityVersion, mappingVersion);
    }

    static String mappingCommitment(byte[] key, int identityVersion, int mappingVersion) {
        if (key == null || key.length < 32 || key.length > 64 || identityVersion < 1 || identityVersion > 65_535
                || mappingVersion < 1 || mappingVersion > 65_535) {
            throw new IllegalArgumentException("MERCHANT_PSEUDONYM_MAPPING_INVALID");
        }
        try {
            String keyCommitment = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key));
            return MerchantCommitment.sha256("MERCHANT_PSEUDONYM_MAPPING_UNAVAILABLE", "merchant-pseudonym-map/v1",
                    identityVersion, mappingVersion, keyCommitment);
        } catch (Exception unavailable) {
            throw new IllegalStateException("MERCHANT_PSEUDONYM_MAPPING_UNAVAILABLE");
        }
    }

    int identityVersion() { return identityVersion; }
    int mappingVersion() { return mappingVersion; }
    String mappingCommitment() { return mappingCommitment; }
    String merchant(String institution, String merchantId) { return mac("merchant", institution, merchantId); }
    String transaction(String institution, String source, String transactionId) { return mac("transaction", institution, source, transactionId); }
    String sourceEvent(String institution, String source, String eventId) { return mac("source-event", institution, source, eventId); }
    String occurrence(String institution, String source, String eventId, String receiptId) {
        return mac("occurrence", institution, source, eventId, receiptId);
    }

    MerchantObservationStore.Scope scope(SourceScope source) {
        Objects.requireNonNull(source, "MERCHANT_SOURCE_SCOPE_REQUIRED");
        return new MerchantObservationStore.Scope(publicToken("issuer", source.issuer()),
                publicToken("institution", source.institution()), publicToken("source", source.source()),
                publicToken("epoch", source.sourceEpoch()), identityVersion);
    }

    private String mac(String purpose, String... fields) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            update(mac, "merchant-admission/" + purpose + "/v1");
            update(mac, Integer.toString(identityVersion));
            update(mac, Integer.toString(mappingVersion));
            for (String field : fields) { update(mac, field); }
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (Exception unavailable) {
            throw new IllegalStateException("MERCHANT_PSEUDONYM_MAPPING_UNAVAILABLE");
        }
    }

    private static String publicToken(String purpose, String value) {
        return MerchantCommitment.sha256("MERCHANT_PSEUDONYM_MAPPING_UNAVAILABLE", "merchant-scope/v1", purpose, value);
    }

    private static void update(Mac mac, String value) {
        if (value == null || value.isBlank() || value.length() > 512) { throw new IllegalArgumentException(); }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        mac.update(bytes);
    }

    @Override public String toString() { return "MerchantPseudonyms[redacted]"; }
}
