package com.frauddetection.common.events.source;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionRawEvent;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Authenticated, private Kafka-header provenance bound to an exact RAW or ENRICHED event. */
public final class SourceOriginProofs {
    public static final String KAFKA_HEADER = "fraud-origin-proof-v1";
    private static final String DOMAIN = "fraud-platform-ingress-origin/v1";
    private final String activeKeyId;
    private final Map<String, byte[]> keys;

    public SourceOriginProofs(SourceOriginProperties properties) {
        Objects.requireNonNull(properties, "SOURCE_ORIGIN_CONFIGURATION_REQUIRED");
        if (!properties.enabled()) { throw new IllegalArgumentException("SOURCE_ORIGIN_CONFIGURATION_DISABLED"); }
        activeKeyId = properties.activeKeyId();
        Map<String, byte[]> configured = new HashMap<>();
        try {
            properties.keys().forEach((id, encoded) -> {
                SourceScope.requireReference(id);
                byte[] key = Base64.getDecoder().decode(encoded);
                if (!Base64.getEncoder().encodeToString(key).equals(encoded) || key.length < 32 || key.length > 64) {
                    throw new IllegalArgumentException();
                }
                configured.put(id, key);
            });
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("SOURCE_ORIGIN_CONFIGURATION_INVALID");
        }
        keys = Map.copyOf(configured);
    }

    public String signRaw(TransactionRawEvent event, Assertion assertion) {
        return sign("raw", event.eventId(), event.createdAt(), SourceEventCommitment.raw(event), assertion);
    }

    public String signEnriched(TransactionEnrichedEvent event, Assertion assertion) {
        return sign("enriched", event.eventId(), event.createdAt(), SourceEventCommitment.enriched(event), assertion);
    }

    public Verification verifyRaw(TransactionRawEvent event, String token) {
        return verify("raw", event.eventId(), event.createdAt(), SourceEventCommitment.raw(event), token);
    }

    public Verification verifyEnriched(TransactionEnrichedEvent event, String token) {
        return verify("enriched", event.eventId(), event.createdAt(), SourceEventCommitment.enriched(event), token);
    }

    private String sign(String stage, String eventId, java.time.Instant createdAt, String commitment, Assertion assertion) {
        Objects.requireNonNull(assertion, "SOURCE_ORIGIN_ASSERTION_REQUIRED");
        byte[] signature = mac(keys.get(activeKeyId), signingBytes(stage, eventId, createdAt, commitment, assertion, activeKeyId));
        return "v1." + encode(activeKeyId.getBytes(StandardCharsets.UTF_8)) + "." + assertion.code + "." + encode(signature);
    }

    private Verification verify(String stage, String eventId, java.time.Instant createdAt, String commitment, String token) {
        if (token == null || token.isBlank()) { return Verification.absent(); }
        try {
            if (token.length() > 512) { return Verification.invalid(); }
            String[] parts = token.split("\\.", -1);
            if (parts.length != 4 || !"v1".equals(parts[0])) { return Verification.invalid(); }
            String keyId = new String(decode(parts[1]), StandardCharsets.UTF_8);
            if (!encode(keyId.getBytes(StandardCharsets.UTF_8)).equals(parts[1])) { return Verification.invalid(); }
            byte[] key = keys.get(keyId);
            Assertion assertion = Assertion.from(parts[2]);
            byte[] provided = decode(parts[3]);
            if (key == null || provided.length != 32) { return Verification.invalid(); }
            byte[] expected = mac(key, signingBytes(stage, eventId, createdAt, commitment, assertion, keyId));
            return MessageDigest.isEqual(expected, provided) ? Verification.verified(assertion) : Verification.invalid();
        } catch (RuntimeException invalid) {
            return Verification.invalid();
        }
    }

    private static byte[] signingBytes(String stage, String eventId, java.time.Instant createdAt, String commitment,
                                       Assertion assertion, String keyId) {
        if (eventId == null || createdAt == null || commitment == null) { throw new IllegalArgumentException("SOURCE_ORIGIN_EVENT_INVALID"); }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                for (String value : new String[] {DOMAIN, stage, assertion.name(), eventId, createdAt.toString(), commitment, keyId}) {
                    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
                    output.writeInt(encoded.length);
                    output.write(encoded);
                }
            }
            return bytes.toByteArray();
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException("SOURCE_ORIGIN_ENCODING_FAILED");
        }
    }

    private static byte[] mac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (java.security.GeneralSecurityException unavailable) {
            throw new IllegalStateException("SOURCE_ORIGIN_VERIFICATION_UNAVAILABLE");
        }
    }

    private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static byte[] decode(String value) { return Base64.getUrlDecoder().decode(value); }

    public enum Assertion {
        PROVEN_LEGACY("L"), KNOWN_ATTESTED("A");
        private final String code;
        Assertion(String code) { this.code = code; }
        private static Assertion from(String code) {
            return switch (code) {
                case "L" -> PROVEN_LEGACY;
                case "A" -> KNOWN_ATTESTED;
                default -> throw new IllegalArgumentException("SOURCE_ORIGIN_ASSERTION_INVALID");
            };
        }
    }

    public record Verification(Status status, Assertion assertion) {
        public Verification {
            if (status == null || (status == Status.VERIFIED) != (assertion != null)) {
                throw new IllegalArgumentException("SOURCE_ORIGIN_VERIFICATION_INVALID");
            }
        }
        static Verification absent() { return new Verification(Status.ABSENT, null); }
        static Verification invalid() { return new Verification(Status.INVALID, null); }
        static Verification verified(Assertion assertion) { return new Verification(Status.VERIFIED, assertion); }
        @Override public String toString() { return "SourceOriginVerification[" + status + "]"; }
    }

    public enum Status { ABSENT, INVALID, VERIFIED }
}
