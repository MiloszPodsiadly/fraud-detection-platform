package com.frauddetection.common.events.source;

import org.bson.Document;
import org.bson.types.Binary;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Exact scored payload is encrypted at rest; independently configured keys never enter Mongo or event contracts. */
public final class SourceOccurrenceCipher {
    static final int MAX_BYTES = 262_144;
    private final String active;
    private final Map<String, SecretKeySpec> keys;
    private final SecureRandom random = new SecureRandom();
    public SourceOccurrenceCipher(String activeKeyId, Map<String, String> configuredKeys) {
        try {
            SourceScope.requireReference(activeKeyId);
            if (configuredKeys == null || configuredKeys.isEmpty() || configuredKeys.size() > 4) { throw new IllegalArgumentException(); }
            Map<String, SecretKeySpec> parsed = new HashMap<>();
            configuredKeys.forEach((id, encoded) -> {
                SourceScope.requireReference(id);
                if (encoded == null || encoded.length() != 44) { throw new IllegalArgumentException(); }
                byte[] bytes = Base64.getDecoder().decode(encoded);
                if (bytes.length != 32 || !Base64.getEncoder().encodeToString(bytes).equals(encoded)) { throw new IllegalArgumentException(); }
                parsed.put(id, new SecretKeySpec(bytes, "AES"));
            });
            if (!parsed.containsKey(activeKeyId)) { throw new IllegalArgumentException(); }
            active = activeKeyId; keys = Map.copyOf(parsed);
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("SOURCE_OCCURRENCE_ENCRYPTION_CONFIGURATION_INVALID"); }
    }
    void encrypt(Document row, byte[] plaintext) {
        if (plaintext.length == 0 || plaintext.length > MAX_BYTES) { throw invalid(); }
        byte[] nonce = new byte[12]; random.nextBytes(nonce);
        row.append("keyId", active).append("nonce", new Binary(nonce));
        row.append("ciphertext", new Binary(crypt(Cipher.ENCRYPT_MODE, row, plaintext)));
    }
    byte[] decrypt(Document row) {
        Binary payload = row.get("ciphertext", Binary.class);
        if (payload == null || payload.length() < 17 || payload.length() > MAX_BYTES + 16) { throw invalid(); }
        return crypt(Cipher.DECRYPT_MODE, row, payload.getData());
    }
    private byte[] crypt(int mode, Document row, byte[] input) {
        try {
            var key = keys.get(row.getString("keyId"));
            Binary nonce = row.get("nonce", Binary.class);
            if (key == null || nonce == null || nonce.length() != 12) { throw invalid(); }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, nonce.getData()));
            cipher.updateAAD(SourceReceiptIdentity.digest("source-scored-outbox/v1", row.getString("_id"), row.getString("receiptId"),
                    row.getString("enrichedCommitment"), row.getString("runId"), row.getString("eventId"), row.getString("eventCreatedAt"),
                    row.getString("fingerprint"), row.getDate("createdAt").toInstant().toString(),
                    row.getDate("expiresAt").toInstant().toString(), row.getString("keyId")).getBytes(StandardCharsets.US_ASCII));
            return cipher.doFinal(input);
        } catch (Exception unavailable) { throw invalid(); }
    }
    private static IllegalStateException invalid() { return new IllegalStateException("SOURCE_OCCURRENCE_PAYLOAD_UNAVAILABLE"); }
    @Override public String toString() { return "SourceOccurrenceCipher[redacted]"; }
}
