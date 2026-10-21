package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import tools.jackson.databind.ObjectMapper;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Publication additionally proves possession of the separately mandated publisher private key. */
final class InternalPolicyPublicationAuthorization implements InstitutionalPolicyPublicationAuthorization {
    private final ObjectMapper mapper;
    private final InternalPolicyApprovalTrustRegistry registry;
    private final InternalPolicyApprovalArtifact artifact;
    private final PrivateKey publisherPrivateKey;
    private final InternalPolicyApprovalProofVerifier proofVerifier;
    private final SecureRandom random = new SecureRandom();

    InternalPolicyPublicationAuthorization(ObjectMapper mapper, InternalPolicyApprovalTrustRegistry registry,
            InternalPolicyApprovalArtifact artifact, PrivateKey publisherPrivateKey, Clock clock) {
        this.mapper = Objects.requireNonNull(mapper);
        this.registry = Objects.requireNonNull(registry);
        this.artifact = Objects.requireNonNull(artifact);
        this.publisherPrivateKey = Objects.requireNonNull(publisherPrivateKey);
        this.proofVerifier = new InternalPolicyApprovalProofVerifier(mapper, registry, artifact, clock);
    }

    InternalPolicyApprovalArtifact approvalArtifact() {
        return artifact;
    }

    @Override
    public Optional<Authorization> authorize(ModelPromotionPolicy.Content content, String canonicalSha256) {
        Optional<Authorization> authorized = proofVerifier.authorize(content, canonicalSha256);
        if (authorized.isEmpty()) return Optional.empty();
        try {
            var author = artifact.authorStatement();
            var key = registry.find(author.publisherIdentity(), author.publisherKeyId());
            return key != null && publisherControlsKey(key.publicKey()) ? authorized : Optional.empty();
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            return Optional.empty();
        }
    }

    private boolean publisherControlsKey(PublicKey expectedKey) throws GeneralSecurityException {
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        byte[] challenge = GovernanceCanonicalJson.serialize(mapper, mapper.valueToTree(Map.of(
                "purpose", InternalPolicyApprovalArtifact.PUBLISHER_CHALLENGE_PURPOSE,
                "contractVersion", InternalPolicyApprovalArtifact.VERSION,
                "approvalProofSha256", InternalPolicyApprovalArtifact.proofSha256(mapper, artifact),
                "nonce", Base64.getUrlEncoder().withoutPadding().encodeToString(nonce))));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(publisherPrivateKey);
        signer.update(challenge);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(expectedKey);
        verifier.update(challenge);
        return verifier.verify(signature);
    }
}
