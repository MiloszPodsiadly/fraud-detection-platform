package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.frauddetection.alert.governance.modelpromotion.InstitutionalPolicyPublicationAuthorization.Authorization;
import tools.jackson.databind.ObjectMapper;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Shared public-key verification of two independent, signed approval statements. */
final class InternalPolicyApprovalProofVerifier {

    private static final long MAX_FUTURE_CLOCK_SKEW_SECONDS = 30;

    private final ObjectMapper mapper;
    private final InternalPolicyApprovalTrustRegistry registry;
    private final InternalPolicyApprovalArtifact artifact;
    private final Clock clock;

    InternalPolicyApprovalProofVerifier(ObjectMapper mapper, InternalPolicyApprovalTrustRegistry registry,
            InternalPolicyApprovalArtifact artifact, Clock clock) {
        this.mapper = Objects.requireNonNull(mapper);
        this.registry = Objects.requireNonNull(registry);
        this.artifact = Objects.requireNonNull(artifact);
        this.clock = Objects.requireNonNull(clock);
    }

    InternalPolicyApprovalArtifact approvalArtifact() {
        return artifact;
    }

    public Optional<Authorization> authorize(ModelPromotionPolicy.Content content, String canonicalSha256) {
        return authorizePublished(content, canonicalSha256, clock.instant());
    }

    Optional<Authorization> authorizePublished(ModelPromotionPolicy.Content content, String canonicalSha256,
            Instant publicationTime) {
        try {
            return verify(content, canonicalSha256, publicationTime);
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            return Optional.empty();
        }
    }

    private Optional<Authorization> verify(ModelPromotionPolicy.Content content, String canonicalSha256,
            Instant publicationTime)
            throws GeneralSecurityException {
        if (content == null || canonicalSha256 == null || content.provenance().authority()
                != ModelPromotionPolicy.PolicyAuthority.INSTITUTIONAL_APPROVED
                || !GovernanceCanonicalJson.sha256(mapper, content).equals(canonicalSha256)) {
            return Optional.empty();
        }
        InternalPolicyApprovalArtifact.AuthorStatement author = artifact.authorStatement();
        InternalPolicyApprovalArtifact.ApprovalDecision decision = artifact.approvalDecision();
        Instant now = clock.instant();
        Instant authoredAt = ModelPromotionTrustedIssuerRegistry.canonicalTime(author.authoredAt(), "authoredAt");
        Instant decidedAt = ModelPromotionTrustedIssuerRegistry.canonicalTime(decision.decidedAt(), "decidedAt");
        Instant expiresAt = ModelPromotionTrustedIssuerRegistry.canonicalTime(decision.expiresAt(), "expiresAt");
        Instant effectiveFrom = ModelPromotionTrustedIssuerRegistry.canonicalTime(
                content.effectiveFromInclusive(), "effectiveFromInclusive");
        Instant effectiveTo = ModelPromotionTrustedIssuerRegistry.canonicalTime(
                content.effectiveToExclusive(), "effectiveToExclusive");
        if (!author.policyId().equals(content.policyId()) || author.revision() != content.revision()
                || !author.draftCanonicalSha256().equals(ModelPromotionPolicyDraft.fromApprovedContent(
                    content, author.publisherIdentity(), author.publisherKeyId(),
                    author.issuerIdentity(), author.issuerKeyId()).canonicalSha256(mapper))
                || !author.governanceScope().equals(content.governanceScope())
                || author.intendedUse() != content.intendedUse()
                || !author.effectiveFromInclusive().equals(content.effectiveFromInclusive())
                || !author.effectiveToExclusive().equals(content.effectiveToExclusive())
                || !author.authorIdentity().equals(content.provenance().authorIdentity())
                || !author.approvalReference().equals(content.provenance().approvalReference())
                || !decision.approverIdentity().equals(content.provenance().approvingAuthority())
                || !decision.decidedAt().equals(content.provenance().approvedAt())
                || !decision.approvalReference().equals(content.provenance().approvalReference())
                || !decision.authorStatementSha256().equals(
                    InternalPolicyApprovalArtifact.authorStatementSha256(mapper, author))
                || decision.decision() != InternalPolicyApprovalArtifact.Decision.APPROVED
                || author.authorIdentity().equals(decision.approverIdentity())
                || author.authorIdentity().equals(author.issuerIdentity())
                || decision.approverIdentity().equals(author.issuerIdentity())
                || authoredAt.isAfter(decidedAt)
                || decidedAt.isAfter(publicationTime.plusSeconds(MAX_FUTURE_CLOCK_SKEW_SECONDS))
                || publicationTime.isBefore(decidedAt) || !publicationTime.isBefore(expiresAt)
                || !decidedAt.isBefore(expiresAt)
                || decidedAt.isAfter(effectiveFrom) || !effectiveFrom.isBefore(effectiveTo)) {
            return Optional.empty();
        }
        InternalPolicyApprovalTrustRegistry.TrustedKey authorKey = registry.find(
                author.authorIdentity(), author.authorKeyId());
        InternalPolicyApprovalTrustRegistry.TrustedKey approverKey = registry.find(
                decision.approverIdentity(), decision.approverKeyId());
        InternalPolicyApprovalTrustRegistry.TrustedKey publisherKey = registry.find(
                author.publisherIdentity(), author.publisherKeyId());
        InternalPolicyApprovalTrustRegistry.TrustedKey issuerKey = registry.find(
                author.issuerIdentity(), author.issuerKeyId());
        ModelGovernanceScope scope = content.governanceScope();
        ModelPromotionIntendedUse use = content.intendedUse();
        if (!authorized(authorKey, InternalPolicyApprovalTrustRegistry.Role.POLICY_AUTHOR, scope, use, authoredAt, now)
                || !authorized(approverKey, InternalPolicyApprovalTrustRegistry.Role.POLICY_APPROVER,
                    scope, use, decidedAt, now)
                || !authorized(publisherKey, InternalPolicyApprovalTrustRegistry.Role.POLICY_PUBLISHER,
                    scope, use, now, now)
                || !authorized(issuerKey, InternalPolicyApprovalTrustRegistry.Role.POLICY_ISSUER,
                    scope, use, now, now)
                || authorKey.publicKeySha256().equals(approverKey.publicKeySha256())
                || approverKey.publicKeySha256().equals(issuerKey.publicKeySha256())
                || !signatureValid(authorKey.publicKey(),
                    InternalPolicyApprovalArtifact.authorSigningBytes(mapper, author), artifact.authorSignature())
                || !signatureValid(approverKey.publicKey(),
                    InternalPolicyApprovalArtifact.approverSigningBytes(mapper, decision), artifact.approverSignature())) {
            return Optional.empty();
        }
        return Optional.of(new Authorization(content.policyId(), content.revision(), canonicalSha256, scope, use,
                effectiveFrom, effectiveTo, author.authorIdentity(), decision.approverIdentity(),
                decision.approvalReference(), author.publisherIdentity(), author.issuerIdentity(), author.issuerKeyId(),
                registry.generation(), registry.canonicalSha256(), approverKey.publicKeySha256(),
                publisherKey.publicKeySha256(), issuerKey.publicKeySha256()));
    }

    private boolean authorized(InternalPolicyApprovalTrustRegistry.TrustedKey key,
            InternalPolicyApprovalTrustRegistry.Role role, ModelGovernanceScope scope,
            ModelPromotionIntendedUse use, Instant signedAt, Instant now) {
        return key != null && key.status() == ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE
                && key.roles().contains(role)
                && key.institutionalScopes().contains(scope.institutionalScope())
                && key.environments().contains(scope.environment()) && key.intendedUses().contains(use)
                && within(key, signedAt) && within(key, now);
    }

    private boolean within(InternalPolicyApprovalTrustRegistry.TrustedKey key, Instant time) {
        return !time.isBefore(key.validFrom()) && time.isBefore(key.validUntil());
    }

    private boolean signatureValid(PublicKey key, byte[] input, String encoded) throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(input);
        return verifier.verify(ModelPromotionPolicySignatureContract.decodeSignature(encoded));
    }

}
