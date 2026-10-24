package com.frauddetection.alert.governance.modelpromotion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalPolicyPublicationAuthorizationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String AUTHOR = "policy-author";
    private static final String APPROVER = "risk-committee";
    private static final String PUBLISHER = "policy-publisher";
    private static final String ISSUER = "institutional-policy-issuer";
    private static final String AUTHOR_KEY = "author-key-1";
    private static final String APPROVER_KEY = "approver-key-1";
    private static final String PUBLISHER_KEY = "publisher-key-1";
    private static final String ISSUER_KEY = "issuer-key-1";

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private KeyPair authorPair;
    private KeyPair approverPair;
    private KeyPair publisherPair;
    private KeyPair issuerPair;
    private ModelPromotionPolicy.Content content;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        authorPair = generator.generateKeyPair();
        approverPair = generator.generateKeyPair();
        publisherPair = generator.generateKeyPair();
        issuerPair = generator.generateKeyPair();
        content = content(AUTHOR, APPROVER, "approval-1", 1, scope(ModelGovernanceEnvironment.PRODUCTION),
                "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z", BigDecimal.ONE);
    }

    @Test
    void independentSignaturesAndAuthenticatedPublisherAuthorizeExactPolicy() throws Exception {
        InternalPolicyApprovalArtifact proof = proof(content, authorPair, approverPair,
                AUTHOR, AUTHOR_KEY, APPROVER, APPROVER_KEY, "2026-06-15T00:00:00Z");
        Optional<InstitutionalPolicyPublicationAuthorization.Authorization> verified = authorization(
                proof, registry(keys()), publisherPair.getPrivate()).authorize(content, digest(content));
        assertThat(verified).isPresent();
        assertThat(verified.orElseThrow().publisherIdentity()).isEqualTo(PUBLISHER);
        assertThat(verified.orElseThrow().issuerIdentity()).isEqualTo(ISSUER);
        assertThat(InternalPolicyApprovalArtifact.proofSha256(mapper, proof)).matches("[0-9a-f]{64}");
    }

    @Test
    void sameActorOrSamePhysicalKeyCannotSelfApprove() throws Exception {
        ModelPromotionPolicy.Content self = content(AUTHOR, AUTHOR, "approval-1", 1,
                scope(ModelGovernanceEnvironment.PRODUCTION), "2026-07-01T00:00:00Z",
                "2026-10-01T00:00:00Z", BigDecimal.ONE);
        assertThatThrownBy(() -> proof(self, authorPair, approverPair,
                AUTHOR, AUTHOR_KEY, AUTHOR, APPROVER_KEY, "2026-06-15T00:00:00Z"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distinct actors");

        assertThatThrownBy(() -> registry(List.of(
                key(AUTHOR, AUTHOR_KEY, authorPair, InternalPolicyApprovalTrustRegistry.Role.POLICY_AUTHOR),
                key(APPROVER, APPROVER_KEY, authorPair, InternalPolicyApprovalTrustRegistry.Role.POLICY_APPROVER),
                key(PUBLISHER, PUBLISHER_KEY, publisherPair, InternalPolicyApprovalTrustRegistry.Role.POLICY_PUBLISHER),
                key(ISSUER, ISSUER_KEY, issuerPair, InternalPolicyApprovalTrustRegistry.Role.POLICY_ISSUER))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("public keys must be globally unique");
    }

    @Test
    void proofCannotBeReplayedAfterDigestThresholdScopePeriodReferenceOrRevisionChanges() throws Exception {
        InternalPolicyApprovalArtifact proof = proof();
        InternalPolicyApprovalTrustRegistry trust = registry(keys());
        assertThat(authorization(proof, trust, publisherPair.getPrivate())
                .authorize(content, "0".repeat(64))).isEmpty();
        List<ModelPromotionPolicy.Content> changed = List.of(
                content(AUTHOR, APPROVER, "approval-1", 1, scope(ModelGovernanceEnvironment.PRODUCTION),
                        "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z", BigDecimal.TWO),
                content(AUTHOR, APPROVER, "approval-1", 1, scope(ModelGovernanceEnvironment.TEST),
                        "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z", BigDecimal.ONE),
                content(AUTHOR, APPROVER, "approval-1", 1, scope(ModelGovernanceEnvironment.PRODUCTION),
                        "2026-07-02T00:00:00Z", "2026-10-01T00:00:00Z", BigDecimal.ONE),
                content(AUTHOR, APPROVER, "approval-1", 1, scope(ModelGovernanceEnvironment.PRODUCTION),
                        "2026-07-01T00:00:00Z", "2026-10-02T00:00:00Z", BigDecimal.ONE),
                content(AUTHOR, APPROVER, "forged-reference", 1, scope(ModelGovernanceEnvironment.PRODUCTION),
                        "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z", BigDecimal.ONE),
                content(AUTHOR, APPROVER, "approval-1", 2, scope(ModelGovernanceEnvironment.PRODUCTION),
                        "2026-07-01T00:00:00Z", "2026-10-01T00:00:00Z", BigDecimal.ONE),
                new ModelPromotionPolicy.Content(content.policyVersion(), content.policyId(), content.revision(),
                        content.governanceScope(), content.intendedUse(),
                        new ModelPromotionPolicy.PolicyProvenance(content.provenance().authority(),
                                AUTHOR, APPROVER, "2026-05-21T00:00:00Z", "approval-1"),
                        content.effectiveFromInclusive(), content.effectiveToExclusive(), content.criteria()));
        for (ModelPromotionPolicy.Content other : changed) {
            assertThat(authorization(proof, trust, publisherPair.getPrivate())
                    .authorize(other, digest(other))).isEmpty();
        }
    }

    @Test
    void unknownRevokedOrUnauthorizedApproverAndIssuerReuseFailClosed() throws Exception {
        InternalPolicyApprovalArtifact proof = proof();
        List<InternalPolicyApprovalTrustRegistry.ConfiguredKey> base = keys();
        assertThat(authorization(proof, registry(List.of(base.get(0), base.get(2), base.get(3))),
                publisherPair.getPrivate()).authorize(content, digest(content))).isEmpty();
        assertThat(authorization(proof, registry(List.of(base.get(0),
                withStatus(base.get(1), ModelPromotionTrustedIssuerRegistry.KeyStatus.REVOKED), base.get(2), base.get(3))),
                publisherPair.getPrivate()).authorize(content, digest(content))).isEmpty();
        assertThat(authorization(proof, registry(List.of(base.get(0),
                key(APPROVER, APPROVER_KEY, approverPair, InternalPolicyApprovalTrustRegistry.Role.POLICY_AUTHOR),
                base.get(2), base.get(3))), publisherPair.getPrivate()).authorize(content, digest(content))).isEmpty();
        assertThatThrownBy(() -> registry(List.of(base.get(0),
                key(APPROVER, APPROVER_KEY, issuerPair, InternalPolicyApprovalTrustRegistry.Role.POLICY_ISSUER),
                base.get(2), base.get(3))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("public keys must be globally unique");
    }

    @Test
    void expiredRejectedTamperedOrUnsignedProofCannotAuthorize() throws Exception {
        InternalPolicyApprovalArtifact valid = proof();
        InternalPolicyApprovalArtifact.ApprovalDecision source = valid.approvalDecision();
        InternalPolicyApprovalArtifact expired = withDecision(valid, new InternalPolicyApprovalArtifact.ApprovalDecision(
                source.contractVersion(), source.purpose(), source.authorStatementSha256(),
                source.approverIdentity(), source.approverKeyId(), source.decision(), source.decidedAt(),
                "2026-05-31T00:00:00Z", source.approvalReference()), approverPair);
        InternalPolicyApprovalArtifact rejected = withDecision(valid, new InternalPolicyApprovalArtifact.ApprovalDecision(
                source.contractVersion(), source.purpose(), source.authorStatementSha256(),
                source.approverIdentity(), source.approverKeyId(), InternalPolicyApprovalArtifact.Decision.REJECTED,
                source.decidedAt(), source.expiresAt(), source.approvalReference()), approverPair);
        InternalPolicyApprovalArtifact alteredReference = new InternalPolicyApprovalArtifact(valid.authorStatement(),
                valid.authorSignature(), new InternalPolicyApprovalArtifact.ApprovalDecision(
                source.contractVersion(), source.purpose(), source.authorStatementSha256(),
                source.approverIdentity(), source.approverKeyId(), source.decision(), source.decidedAt(),
                source.expiresAt(), "forged-reference"), valid.approverSignature());
        InternalPolicyApprovalArtifact unsigned = new InternalPolicyApprovalArtifact(valid.authorStatement(),
                "not-a-signature", source, valid.approverSignature());
        InternalPolicyApprovalArtifact alteredApprovalSignature = new InternalPolicyApprovalArtifact(
                valid.authorStatement(), valid.authorSignature(), source, "not-a-signature");
        for (InternalPolicyApprovalArtifact invalid : List.of(expired, rejected, alteredReference, unsigned,
                alteredApprovalSignature)) {
            assertThat(authorization(invalid, registry(keys()), publisherPair.getPrivate())
                    .authorize(content, digest(content))).isEmpty();
        }
        assertThatThrownBy(() -> new InternalPolicyApprovalArtifact(valid.authorStatement(), null,
                source, valid.approverSignature())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actualPublisherMustControlMandatedKeyAndAllRolesRespectEnvironment() throws Exception {
        InternalPolicyApprovalArtifact valid = proof();
        assertThat(authorization(valid, registry(keys()), authorPair.getPrivate())
                .authorize(content, digest(content))).isEmpty();
        ModelPromotionPolicy.Content testContent = content(AUTHOR, APPROVER, "approval-1", 1,
                scope(ModelGovernanceEnvironment.TEST), "2026-07-01T00:00:00Z",
                "2026-10-01T00:00:00Z", BigDecimal.ONE);
        InternalPolicyApprovalArtifact testProof = proof(testContent, authorPair, approverPair,
                AUTHOR, AUTHOR_KEY, APPROVER, APPROVER_KEY, "2026-06-15T00:00:00Z");
        assertThat(authorization(testProof, registry(keys()), publisherPair.getPrivate())
                .authorize(testContent, digest(testContent))).isEmpty();
    }

    @Test
    void registryMissingMalformedOrRolledBackCannotSupplyApprovalTrust() throws Exception {
        assertThatThrownBy(() -> InternalPolicyApprovalTrustRegistry.load(mapper,
                tempDir.resolve("missing.json"), 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InternalPolicyApprovalTrustRegistry.fromConfiguration(
                new InternalPolicyApprovalTrustRegistry.Configuration(InternalPolicyApprovalTrustRegistry.VERSION,
                        1, keys()), 2)).isInstanceOf(IllegalStateException.class);
        List<InternalPolicyApprovalTrustRegistry.ConfiguredKey> base = keys();
        assertThatThrownBy(() -> registry(List.of(base.get(0), base.get(0))))
                .isInstanceOf(IllegalStateException.class);
        InternalPolicyApprovalTrustRegistry.ConfiguredKey author = base.get(0);
        assertThatThrownBy(() -> registry(List.of(author, new InternalPolicyApprovalTrustRegistry.ConfiguredKey(
                "alias-author", "alias-key", author.publicKeySpkiBase64(), author.publicKeySha256(),
                author.roles(), author.institutionalScopes(), author.environments(), author.intendedUses(),
                author.validFrom(), author.validUntil(), ModelPromotionTrustedIssuerRegistry.KeyStatus.REVOKED))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("public keys must be globally unique");
        assertThat(authorization(proof(), registry(List.of(base.get(0),
                withValidUntil(base.get(1), "2026-05-31T00:00:00Z"), base.get(2), base.get(3))),
                publisherPair.getPrivate()).authorize(content, digest(content))).isEmpty();
    }

    private InternalPolicyApprovalArtifact proof() throws Exception {
        return proof(content, authorPair, approverPair, AUTHOR, AUTHOR_KEY, APPROVER, APPROVER_KEY,
                "2026-06-15T00:00:00Z");
    }

    private InternalPolicyApprovalArtifact proof(ModelPromotionPolicy.Content policy,
            KeyPair authorSigner, KeyPair approverSigner, String authorIdentity, String authorKeyId,
            String approverIdentity, String approverKeyId, String expiresAt) throws Exception {
        ModelPromotionPolicyDraft draft = ModelPromotionPolicyDraft.fromApprovedContent(
                policy, PUBLISHER, PUBLISHER_KEY, ISSUER, ISSUER_KEY);
        InternalPolicyApprovalArtifact.AuthorStatement author = new InternalPolicyApprovalArtifact.AuthorStatement(
                InternalPolicyApprovalArtifact.VERSION, InternalPolicyApprovalArtifact.AUTHOR_PURPOSE,
                policy.policyId(), policy.revision(), draft.canonicalSha256(mapper), policy.governanceScope(), policy.intendedUse(),
                policy.effectiveFromInclusive(), policy.effectiveToExclusive(), policy.provenance().approvalReference(),
                authorIdentity, authorKeyId,
                PUBLISHER, PUBLISHER_KEY, ISSUER, ISSUER_KEY, "2026-05-19T00:00:00Z");
        InternalPolicyApprovalArtifact.ApprovalDecision decision = new InternalPolicyApprovalArtifact.ApprovalDecision(
                InternalPolicyApprovalArtifact.VERSION, InternalPolicyApprovalArtifact.APPROVER_PURPOSE,
                InternalPolicyApprovalArtifact.authorStatementSha256(mapper, author), approverIdentity, approverKeyId,
                InternalPolicyApprovalArtifact.Decision.APPROVED, policy.provenance().approvedAt(), expiresAt,
                policy.provenance().approvalReference());
        return new InternalPolicyApprovalArtifact(author,
                sign(authorSigner, InternalPolicyApprovalArtifact.authorSigningBytes(mapper, author)), decision,
                sign(approverSigner, InternalPolicyApprovalArtifact.approverSigningBytes(mapper, decision)));
    }

    private InternalPolicyApprovalArtifact withDecision(InternalPolicyApprovalArtifact source,
            InternalPolicyApprovalArtifact.ApprovalDecision decision, KeyPair signer) throws Exception {
        return new InternalPolicyApprovalArtifact(source.authorStatement(), source.authorSignature(), decision,
                sign(signer, InternalPolicyApprovalArtifact.approverSigningBytes(mapper, decision)));
    }

    private String sign(KeyPair pair, byte[] input) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(input);
        return ModelPromotionPolicySignatureContract.encodeSignature(signer.sign());
    }

    private InternalPolicyPublicationAuthorization authorization(InternalPolicyApprovalArtifact artifact,
            InternalPolicyApprovalTrustRegistry trust, PrivateKey publisherCredential) {
        return new InternalPolicyPublicationAuthorization(mapper, trust, artifact, publisherCredential, CLOCK);
    }

    private InternalPolicyApprovalTrustRegistry registry(List<InternalPolicyApprovalTrustRegistry.ConfiguredKey> entries) {
        return InternalPolicyApprovalTrustRegistry.fromConfiguration(new InternalPolicyApprovalTrustRegistry.Configuration(
                InternalPolicyApprovalTrustRegistry.VERSION, 1, entries), 1);
    }

    private List<InternalPolicyApprovalTrustRegistry.ConfiguredKey> keys() throws Exception {
        return List.of(
                key(AUTHOR, AUTHOR_KEY, authorPair, InternalPolicyApprovalTrustRegistry.Role.POLICY_AUTHOR),
                key(APPROVER, APPROVER_KEY, approverPair, InternalPolicyApprovalTrustRegistry.Role.POLICY_APPROVER),
                key(PUBLISHER, PUBLISHER_KEY, publisherPair, InternalPolicyApprovalTrustRegistry.Role.POLICY_PUBLISHER),
                key(ISSUER, ISSUER_KEY, issuerPair, InternalPolicyApprovalTrustRegistry.Role.POLICY_ISSUER));
    }

    private InternalPolicyApprovalTrustRegistry.ConfiguredKey key(String identity, String keyId, KeyPair pair,
            InternalPolicyApprovalTrustRegistry.Role role) throws Exception {
        byte[] encoded = pair.getPublic().getEncoded();
        return new InternalPolicyApprovalTrustRegistry.ConfiguredKey(identity, keyId,
                Base64.getEncoder().encodeToString(encoded),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)),
                List.of(role), List.of(ModelGovernanceScope.CURRENT_INSTITUTION),
                List.of(ModelGovernanceEnvironment.PRODUCTION), List.of(ModelPromotionIntendedUse.FRAUD_RISK_SCORING),
                "2025-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE);
    }

    private InternalPolicyApprovalTrustRegistry.ConfiguredKey withStatus(
            InternalPolicyApprovalTrustRegistry.ConfiguredKey key, ModelPromotionTrustedIssuerRegistry.KeyStatus status) {
        return new InternalPolicyApprovalTrustRegistry.ConfiguredKey(key.identity(), key.keyId(),
                key.publicKeySpkiBase64(), key.publicKeySha256(), key.roles(), key.institutionalScopes(),
                key.environments(), key.intendedUses(), key.validFrom(), key.validUntil(), status);
    }

    private InternalPolicyApprovalTrustRegistry.ConfiguredKey withValidUntil(
            InternalPolicyApprovalTrustRegistry.ConfiguredKey key, String validUntil) {
        return new InternalPolicyApprovalTrustRegistry.ConfiguredKey(key.identity(), key.keyId(),
                key.publicKeySpkiBase64(), key.publicKeySha256(), key.roles(), key.institutionalScopes(),
                key.environments(), key.intendedUses(), key.validFrom(), validUntil, key.status());
    }

    private ModelPromotionPolicy.Content content(String author, String approver, String reference, long revision,
            ModelGovernanceScope scope, String from, String to, BigDecimal threshold) {
        List<ModelPromotionPolicy.CriterionRule> rules = java.util.Arrays.stream(ModelPromotionCriterion.values())
                .map(criterion -> new ModelPromotionPolicy.CriterionRule(criterion,
                        ModelPromotionPolicy.CriterionApplicability.MANDATORY,
                        criterion.requiredThresholdKeys().stream().collect(java.util.stream.Collectors.toMap(
                                key -> key, key -> threshold)), 86400, "VERIFIED", criterion.evidenceSource(),
                        criterion.metricSemantics(), criterion.populationDefinition(),
                        criterion.denominatorDefinition(), null))
                .toList();
        return new ModelPromotionPolicy.Content(ModelPromotionPolicy.CURRENT_VERSION, "policy-1", revision,
                scope, ModelPromotionIntendedUse.FRAUD_RISK_SCORING,
                new ModelPromotionPolicy.PolicyProvenance(ModelPromotionPolicy.PolicyAuthority.INSTITUTIONAL_APPROVED,
                        author, approver, "2026-05-20T00:00:00Z", reference), from, to, rules);
    }

    private ModelGovernanceScope scope(ModelGovernanceEnvironment environment) {
        return new ModelGovernanceScope(ModelGovernanceScope.CURRENT_INSTITUTION, environment);
    }

    private String digest(ModelPromotionPolicy.Content policy) {
        return new ModelPromotionPolicyValidator(mapper).canonicalSha256(policy);
    }
}
