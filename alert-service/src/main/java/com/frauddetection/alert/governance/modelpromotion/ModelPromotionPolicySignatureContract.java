package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.GovernanceTimestampContract;
import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** The sole byte contract for institutional model-promotion policy signatures. */
public final class ModelPromotionPolicySignatureContract {

    public static final String PURPOSE = "MODEL_PROMOTION_POLICY_APPROVAL_V1";
    public static final String CONTRACT_VERSION = "model-promotion-policy-signature-v1";

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final int ED25519_SIGNATURE_BYTES = 64;

    private ModelPromotionPolicySignatureContract() {
    }

    public static byte[] signingBytes(
            ObjectMapper objectMapper,
            ModelPromotionPolicy.Content content,
            String authorityIdentity,
            String keyId,
            String issuedAt
    ) {
        Objects.requireNonNull(objectMapper, "objectMapper is required");
        Objects.requireNonNull(content, "content is required");
        if (content.provenance().authority() != ModelPromotionPolicy.PolicyAuthority.INSTITUTIONAL_APPROVED) {
            throw new IllegalArgumentException("Only institutional policies have a signed approval envelope");
        }
        identifier(authorityIdentity, "authorityIdentity");
        identifier(keyId, "keyId");
        ModelPromotionPolicy.PolicyProvenance provenance = content.provenance();
        if (provenance.authorIdentity().equals(provenance.approvingAuthority())
                || authorityIdentity.equals(provenance.authorIdentity())
                || authorityIdentity.equals(provenance.approvingAuthority())) {
            throw new IllegalArgumentException("Policy author, approver and signing issuer must have distinct identities");
        }
        Instant issued = canonicalIssuedAt(issuedAt);
        Instant approved = GovernanceTimestampContract.parse(provenance.approvedAt(), "approvedAt");
        Instant effectiveFrom = GovernanceTimestampContract.parse(content.effectiveFromInclusive(), "effectiveFromInclusive");
        if (issued.isBefore(approved) || issued.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("Signature issuance must follow approval and precede policy effectiveness");
        }
        String policyDigest = GovernanceCanonicalJson.sha256(objectMapper, content);
        Map<String, String> envelope = Map.of(
                "purpose", PURPOSE,
                "contractVersion", CONTRACT_VERSION,
                "policyCanonicalSha256", policyDigest,
                "authorityIdentity", authorityIdentity,
                "keyId", keyId,
                "issuedAt", issuedAt
        );
        return GovernanceCanonicalJson.serialize(objectMapper, objectMapper.valueToTree(envelope));
    }

    public static byte[] verificationBytes(ObjectMapper objectMapper, ModelPromotionPolicy policy) {
        Objects.requireNonNull(policy, "policy is required");
        ModelPromotionPolicy.PolicyAuthorizationEvidence evidence = Objects.requireNonNull(
                policy.authorizationEvidence(), "authorizationEvidence is required"
        );
        String actualDigest = GovernanceCanonicalJson.sha256(objectMapper, policy.content());
        if (!actualDigest.equals(policy.canonicalSha256())
                || !actualDigest.equals(evidence.policyCanonicalSha256())) {
            throw new IllegalArgumentException("Signed policy digest does not match canonical content");
        }
        return signingBytes(objectMapper, policy.content(), evidence.authorityIdentity(), evidence.keyId(),
                evidence.issuedAt());
    }

    public static byte[] decodeSignature(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("Ed25519 signature is required");
        }
        byte[] signature;
        try {
            signature = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Ed25519 signature must be canonical unpadded base64url", exception);
        }
        if (signature.length != ED25519_SIGNATURE_BYTES || !encodeSignature(signature).equals(encoded)) {
            throw new IllegalArgumentException("Ed25519 signature must be canonical unpadded base64url");
        }
        return signature;
    }

    public static String encodeSignature(byte[] signature) {
        if (signature == null || signature.length != ED25519_SIGNATURE_BYTES) {
            throw new IllegalArgumentException("Ed25519 signature must contain 64 bytes");
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    static Instant canonicalIssuedAt(String value) {
        Instant issued = GovernanceTimestampContract.parse(value, "issuedAt");
        if (!issued.toString().equals(value)) {
            throw new IllegalArgumentException("issuedAt must use canonical UTC Instant representation");
        }
        return issued;
    }

    private static void identifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
