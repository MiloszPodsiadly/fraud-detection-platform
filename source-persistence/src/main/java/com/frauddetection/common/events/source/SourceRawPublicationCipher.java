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

/** Encrypts the exact, replayable RAW event snapshot at rest with receipt-bound authenticated data. */
public final class SourceRawPublicationCipher {
    public static final int MAX_BYTES = 524_288;
    private final String active;
    private final Map<String, SecretKeySpec> keys;
    private final SecureRandom random = new SecureRandom();

    public SourceRawPublicationCipher(String activeKeyId, Map<String, String> configuredKeys) {
        try {
            SourceScope.requireReference(activeKeyId);
            if (configuredKeys == null || configuredKeys.isEmpty() || configuredKeys.size() > 4) {
                throw new IllegalArgumentException();
            }
            Map<String, SecretKeySpec> parsed = new HashMap<>();
            configuredKeys.forEach((id, encoded) -> {
                SourceScope.requireReference(id);
                if (encoded == null || encoded.length() != 44) { throw new IllegalArgumentException(); }
                byte[] bytes = Base64.getDecoder().decode(encoded);
                if (bytes.length != 32 || !Base64.getEncoder().encodeToString(bytes).equals(encoded)) {
                    throw new IllegalArgumentException();
                }
                parsed.put(id, new SecretKeySpec(bytes, "AES"));
            });
            if (!parsed.containsKey(activeKeyId)) { throw new IllegalArgumentException(); }
            active = activeKeyId;
            keys = Map.copyOf(parsed);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("SOURCE_RAW_PUBLICATION_ENCRYPTION_CONFIGURATION_INVALID");
        }
    }

    public void encrypt(Document row, byte[] plaintext) {
        if (plaintext == null || plaintext.length == 0 || plaintext.length > MAX_BYTES) { throw unavailable(); }
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        row.append("keyId", active).append("nonce", new Binary(nonce));
        row.append("ciphertext", new Binary(crypt(Cipher.ENCRYPT_MODE, row, plaintext)));
    }

    public byte[] decrypt(Document row) {
        Binary payload = row.get("ciphertext", Binary.class);
        if (payload == null || payload.length() < 17 || payload.length() > MAX_BYTES + 16) { throw unavailable(); }
        return crypt(Cipher.DECRYPT_MODE, row, payload.getData());
    }

    private byte[] crypt(int mode, Document row, byte[] input) {
        try {
            SecretKeySpec key = keys.get(row.getString("keyId"));
            Binary nonce = row.get("nonce", Binary.class);
            if (key == null || nonce == null || nonce.length() != 12) { throw unavailable(); }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, nonce.getData()));
            cipher.updateAAD(SourceReceiptIdentity.digest("source-raw-publication/v1", row.getString("_id"),
                    row.getString("receiptId"), row.getString("rawCommitment"), row.getString("eventId"),
                    row.getString("eventCreatedAt"), row.getDate("expiresAt").toInstant().toString(),
                    row.getString("keyId")).getBytes(StandardCharsets.US_ASCII));
            return cipher.doFinal(input);
        } catch (Exception unavailable) {
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("SOURCE_RAW_PUBLICATION_PAYLOAD_UNAVAILABLE");
    }

    @Override public String toString() { return "SourceRawPublicationCipher[redacted]"; }
}
