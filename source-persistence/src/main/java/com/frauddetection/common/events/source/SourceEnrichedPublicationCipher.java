package com.frauddetection.common.events.source;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import org.bson.Document;
import org.bson.types.Binary;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Encrypts the exact enriched event used for lineage-bound retry publication. */
public final class SourceEnrichedPublicationCipher {
    public static final String COLLECTION = "source_enriched_publications";
    public static final int MAX_BYTES = SourceEventCommitment.MAX_EVENT_BYTES;
    private static final JsonMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private final String active;
    private final Map<String, SecretKeySpec> keys;
    private final SecureRandom random = new SecureRandom();

    public SourceEnrichedPublicationCipher(String activeKeyId, Map<String, String> configuredKeys) {
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
            throw new IllegalArgumentException("SOURCE_ENRICHED_PUBLICATION_ENCRYPTION_CONFIGURATION_INVALID");
        }
    }

    public void encrypt(Document row, TransactionEnrichedEvent event) {
        if (row == null || event == null) { throw unavailable(); }
        try {
            BoundedBuffer output = new BoundedBuffer();
            MAPPER.writeValue(output, event);
            byte[] plaintext = output.toByteArray();
            if (plaintext.length == 0) { throw unavailable(); }
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            row.append("keyId", active).append("nonce", new Binary(nonce));
            row.append("ciphertext", new Binary(crypt(Cipher.ENCRYPT_MODE, row, plaintext)));
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    public TransactionEnrichedEvent decrypt(Document row) {
        try {
            Binary payload = row.get("ciphertext", Binary.class);
            if (payload == null || payload.length() < 17 || payload.length() > MAX_BYTES + 16) { throw unavailable(); }
            return MAPPER.readValue(crypt(Cipher.DECRYPT_MODE, row, payload.getData()), TransactionEnrichedEvent.class);
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    private byte[] crypt(int mode, Document row, byte[] input) {
        try {
            SecretKeySpec key = keys.get(row.getString("keyId"));
            Binary nonce = row.get("nonce", Binary.class);
            if (key == null || nonce == null || nonce.length() != 12) { throw unavailable(); }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, nonce.getData()));
            cipher.updateAAD(SourceReceiptIdentity.digest("source-enriched-publication/v1", row.getString("_id"),
                    row.getString("receiptId"), row.getString("rawCommitment"), row.getString("enrichedCommitment"),
                    row.getString("eventId"), row.getString("eventCreatedAt"), row.getDate("expiresAt").toInstant().toString(),
                    row.getString("keyId")).getBytes(StandardCharsets.US_ASCII));
            return cipher.doFinal(input);
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("SOURCE_ENRICHED_PUBLICATION_PAYLOAD_UNAVAILABLE");
    }

    private static final class BoundedBuffer extends ByteArrayOutputStream {
        @Override public synchronized void write(int value) {
            if (count == MAX_BYTES) { throw unavailable(); }
            super.write(value);
        }
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            if (length > MAX_BYTES - count) { throw unavailable(); }
            super.write(bytes, offset, length);
        }
    }

    @Override public String toString() { return "SourceEnrichedPublicationCipher[redacted]"; }
}
