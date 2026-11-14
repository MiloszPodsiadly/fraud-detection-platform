package com.frauddetection.common.events.source;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/** Verifies source evidence only. Durable admission and transaction validation are separate gates. */
public final class SourceSignatureVerifier {
    private static final JsonMapper PROOF_MAPPER = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(3).maxStringLength(512).maxNumberLength(8).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Map<KeyReference, Anchor> anchors;
    private final Clock clock;

    public SourceSignatureVerifier(SourceVerificationProperties properties, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        Map<KeyReference, Anchor> configured = new HashMap<>();
        for (SourceVerificationProperties.TrustedKey policy : properties.keys()) {
            try {
                byte[] encoded = Base64.getDecoder().decode(policy.publicKeyBase64());
                if (!Base64.getEncoder().encodeToString(encoded).equals(policy.publicKeyBase64())) {
                    throw new IllegalArgumentException("SOURCE_TRUST_KEY_INVALID");
                }
                PublicKey key = KeyFactory.getInstance(SourceSignedInput.ALGORITHM)
                        .generatePublic(new X509EncodedKeySpec(encoded));
                KeyReference reference = new KeyReference(policy.issuer(), policy.sourceEpoch(), policy.signingKeyVersion());
                String keyCommitment = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
                if (configured.putIfAbsent(reference, new Anchor(policy, key, keyCommitment)) != null) {
                    throw new IllegalArgumentException("SOURCE_TRUST_KEY_DUPLICATE");
                }
            } catch (GeneralSecurityException | IllegalArgumentException invalid) {
                throw new IllegalArgumentException("SOURCE_TRUST_POLICY_INVALID");
            }
        }
        this.anchors = Map.copyOf(configured);
    }

    public Result verify(byte[] proof, byte[] originalPayload, Context context) {
        if (proof == null || proof.length == 0) { return Denied.PROOF_ABSENT; }
        if (proof.length > SourceSignedInput.MAX_PROOF_BYTES) { return Denied.PROOF_TOO_LARGE; }
        if (originalPayload == null || originalPayload.length == 0) { return Denied.PAYLOAD_ABSENT; }
        if (originalPayload.length > SourceSignedInput.MAX_PAYLOAD_BYTES) { return Denied.PAYLOAD_TOO_LARGE; }
        if (context == null) { return Denied.CONTEXT_NOT_AUTHORIZED; }
        byte[] payload = originalPayload.clone();
        Proof parsed;
        try {
            parsed = parse(proof.clone());
        } catch (RuntimeException malformed) {
            return Denied.MALFORMED_PROOF;
        }
        if (parsed.version != SourceSignedInput.VERSION) { return Denied.UNSUPPORTED_PROTOCOL; }
        if (!SourceSignedInput.ALGORITHM.equals(parsed.algorithm)) { return Denied.UNSUPPORTED_ALGORITHM; }
        SourceScope scope = parsed.scope;
        Anchor anchor = anchors.get(new KeyReference(scope.issuer(), scope.sourceEpoch(), scope.signingKeyVersion()));
        if (anchor == null) { return Denied.UNKNOWN_TRUST_ANCHOR; }
        var policy = anchor.policy;
        if (policy.revoked()) { return Denied.TRUST_ANCHOR_REVOKED; }
        Instant now = clock.instant();
        if (now.isBefore(policy.notBefore()) || !now.isBefore(policy.notAfter())
                || scope.eventTime().isBefore(policy.notBefore()) || !scope.eventTime().isBefore(policy.notAfter())
                || scope.eventTime().isAfter(now.plusSeconds(300))) {
            return Denied.EPOCH_INACTIVE;
        }
        if (!SourceSignedInput.payloadCommitment(payload).equals(scope.payloadCommitment())) {
            return Denied.PAYLOAD_COMMITMENT_MISMATCH;
        }
        try {
            Signature verifier = Signature.getInstance(SourceSignedInput.ALGORITHM);
            verifier.initVerify(anchor.key);
            verifier.update(SourceSignedInput.encode(scope, payload));
            if (!verifier.verify(parsed.signature)) { return Denied.INVALID_SIGNATURE; }
        } catch (GeneralSecurityException invalid) {
            return Denied.INVALID_SIGNATURE;
        } catch (RuntimeException unavailable) {
            return Denied.VERIFICATION_UNAVAILABLE;
        }
        // Authentication of the key does not authorize a caller-selected institutional scope or purpose.
        if (!policy.institution().equals(scope.institution()) || !policy.source().equals(scope.source())) {
            return Denied.SOURCE_SCOPE_NOT_AUTHORIZED;
        }
        if (!policy.purpose().equals(scope.purpose()) || !context.purpose().equals(scope.purpose())) {
            return Denied.PURPOSE_NOT_AUTHORIZED;
        }
        if (!policy.audience().equals(scope.audience()) || !context.audience().equals(scope.audience())) {
            return Denied.AUDIENCE_NOT_AUTHORIZED;
        }
        return new Verified(scope, policy.policyVersion(), anchor.keyCommitment, now);
    }

    /** Rechecks the current server policy, including replacement of a key under a reused key ID. */
    public Denied currentDenial(Verified evidence) {
        if (evidence == null) { return Denied.PROOF_ABSENT; }
        return receiptPolicyDenial(evidence.scope, evidence.keyCommitment, evidence.policyVersion, evidence.verifiedAt);
    }

    /** Policy check only, never a replacement for cryptographic verification or authenticated receipt ownership. */
    public Denied receiptPolicyDenial(SourceScope scope, String keyCommitment, String policyVersion, Instant verifiedAt) {
        if (scope == null || verifiedAt == null) { return Denied.MALFORMED_PROOF; }
        return receiptPolicyDenial(scope.issuer(), scope.institution(), scope.source(), scope.sourceEpoch(),
                scope.signingKeyVersion(), scope.eventTime(), scope.purpose(), scope.audience(), keyCommitment,
                policyVersion, verifiedAt);
    }

    /** Rechecks policy fields from a trusted parser of an already authenticated durable record; this does not authenticate the record. */
    public Denied receiptPolicyDenialForPersistedScope(String issuer, String institution, String source, String sourceEpoch,
                                                       String signingKeyVersion, Instant eventTime, String purpose, String audience,
                                                       String keyCommitment, String policyVersion, Instant verifiedAt) {
        if (issuer == null || institution == null || source == null || sourceEpoch == null || signingKeyVersion == null
                || eventTime == null || purpose == null || audience == null || verifiedAt == null) { return Denied.MALFORMED_PROOF; }
        return receiptPolicyDenial(issuer, institution, source, sourceEpoch, signingKeyVersion, eventTime, purpose, audience,
                keyCommitment, policyVersion, verifiedAt);
    }

    private Denied receiptPolicyDenial(String issuer, String institution, String source, String sourceEpoch,
                                       String signingKeyVersion, Instant eventTime, String purpose, String audience,
                                       String keyCommitment, String policyVersion, Instant verifiedAt) {
        Anchor anchor = anchors.get(new KeyReference(issuer, sourceEpoch, signingKeyVersion));
        if (anchor == null) { return Denied.UNKNOWN_TRUST_ANCHOR; }
        var policy = anchor.policy;
        if (policy.revoked()) { return Denied.TRUST_ANCHOR_REVOKED; }
        if (!anchor.keyCommitment.equals(keyCommitment) || !policy.policyVersion().equals(policyVersion)) {
            return Denied.TRUST_POLICY_CHANGED;
        }
        Instant now = clock.instant();
        if (verifiedAt.isBefore(policy.notBefore()) || !verifiedAt.isBefore(policy.notAfter())
                || verifiedAt.isAfter(now.plusSeconds(300))) { return Denied.EPOCH_INACTIVE; }
        if (now.isBefore(policy.notBefore()) || !now.isBefore(policy.notAfter())
                || eventTime.isBefore(policy.notBefore()) || !eventTime.isBefore(policy.notAfter())
                || eventTime.isAfter(now.plusSeconds(300))) { return Denied.EPOCH_INACTIVE; }
        if (!policy.institution().equals(institution) || !policy.source().equals(source)
                || !policy.purpose().equals(purpose) || !policy.audience().equals(audience)) {
            return Denied.SOURCE_SCOPE_NOT_AUTHORIZED;
        }
        return null;
    }

    private Proof parse(byte[] bytes) {
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode root = PROOF_MAPPER.readTree(json);
            if (root == null || !root.isObject() || root.size() != 13
                    || !root.path("protocolVersion").isIntegralNumber()
                    || !root.path("protocolVersion").canConvertToInt()) {
                throw new IllegalArgumentException("SOURCE_PROOF_INVALID");
            }
            String timestamp = text(root, "eventTime");
            Instant eventTime = Instant.parse(timestamp);
            if (!eventTime.toString().equals(timestamp)) { throw new IllegalArgumentException("SOURCE_PROOF_INVALID"); }
            SourceScope scope = new SourceScope(text(root, "issuer"), text(root, "institution"),
                    text(root, "source"), text(root, "sourceEpoch"), text(root, "signingKeyVersion"),
                    text(root, "sourceEventId"), eventTime, text(root, "purpose"), text(root, "audience"),
                    text(root, "payloadCommitment"));
            String encodedSignature = text(root, "signature");
            if (encodedSignature.length() != 86) { throw new IllegalArgumentException("SOURCE_PROOF_INVALID"); }
            byte[] signature = Base64.getUrlDecoder().decode(encodedSignature);
            if (signature.length != 64 || !Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
                    .equals(encodedSignature)) { throw new IllegalArgumentException("SOURCE_PROOF_INVALID"); }
            return new Proof(root.get("protocolVersion").intValue(), text(root, "algorithm"), scope, signature);
        } catch (Exception malformed) {
            throw new IllegalArgumentException("SOURCE_PROOF_INVALID");
        }
    }

    private String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual()) { throw new IllegalArgumentException("SOURCE_PROOF_INVALID"); }
        return value.textValue();
    }

    public record Context(String purpose, String audience) {
        public Context { SourceScope.requireReference(purpose); SourceScope.requireReference(audience); }
    }
    public sealed interface Result permits Verified, Denied { }
    /** Immutable verification evidence, not a durable admission receipt or history-completeness grant. */
    public static final class Verified implements Result {
        private final SourceScope scope;
        private final String policyVersion;
        private final String keyCommitment;
        private final Instant verifiedAt;
        private Verified(SourceScope scope, String policyVersion, String keyCommitment, Instant verifiedAt) {
            this.scope = scope; this.policyVersion = policyVersion; this.keyCommitment = keyCommitment;
            this.verifiedAt = verifiedAt;
        }
        public SourceScope scope() { return scope; }
        public String policyVersion() { return policyVersion; }
        public String keyCommitment() { return keyCommitment; }
        public Instant verifiedAt() { return verifiedAt; }
        @Override public String toString() { return "VerifiedSourceEvidence[redacted]"; }
    }
    public enum Denied implements Result {
        PROOF_ABSENT, PROOF_TOO_LARGE, PAYLOAD_ABSENT, PAYLOAD_TOO_LARGE, CONTEXT_NOT_AUTHORIZED,
        MALFORMED_PROOF, UNSUPPORTED_PROTOCOL, UNSUPPORTED_ALGORITHM, UNKNOWN_TRUST_ANCHOR,
        TRUST_ANCHOR_REVOKED, TRUST_POLICY_CHANGED, EPOCH_INACTIVE, PAYLOAD_COMMITMENT_MISMATCH, INVALID_SIGNATURE,
        VERIFICATION_UNAVAILABLE, SOURCE_SCOPE_NOT_AUTHORIZED, PURPOSE_NOT_AUTHORIZED, AUDIENCE_NOT_AUTHORIZED
    }
    private record KeyReference(String issuer, String epoch, String keyVersion) { }
    private record Anchor(SourceVerificationProperties.TrustedKey policy, PublicKey key, String keyCommitment) { }
    private record Proof(int version, String algorithm, SourceScope scope, byte[] signature) { }
}
