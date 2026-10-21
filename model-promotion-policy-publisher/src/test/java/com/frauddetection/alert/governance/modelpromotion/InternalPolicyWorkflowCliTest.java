package com.frauddetection.alert.governance.modelpromotion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalPolicyWorkflowCliTest {

    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void repositoryCliProducesSignedAuthorSubmissionIndependentDecisionAndVerifiedDryRun() throws Exception {
        Fixture fixture = fixture();
        ModelPromotionPolicyPublisherCli.main(new String[] {"trust-check", "--approval-trust",
                fixture.approvalTrust().toString(), "--trusted-issuers", fixture.issuerTrust().toString()});
        Path submission = directory.resolve("author.json");
        Path approval = directory.resolve("approval.json");
        ModelPromotionPolicyPublisherCli.main(new String[] {"author", "--policy", fixture.policy().toString(),
                "--approval-trust", fixture.approvalTrust().toString(), "--author-private-key",
                fixture.authorPrivate().toString(), "--author-identity", "local-author-a", "--author-key-id",
                "author-key", "--out", submission.toString()});
        assertThat(Files.readString(submission)).contains("authorSignature");
        Instant beforeReview = Instant.now();
        ModelPromotionPolicyPublisherCli.main(new String[] {"approve", "--submission", submission.toString(),
                "--approval-trust", fixture.approvalTrust().toString(), "--approver-private-key",
                fixture.approverPrivate().toString(), "--approver-identity", "local-approver-b",
                "--approver-key-id", "approver-key", "--decision", "APPROVED", "--expires-at",
                Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString(),
                "--out", approval.toString()});
        InternalPolicyApprovalArtifact proof = mapper.readValue(Files.readString(approval),
                InternalPolicyApprovalArtifact.class);
        assertThat(proof.approvalDecision().decision()).isEqualTo(InternalPolicyApprovalArtifact.Decision.APPROVED);
        assertThat(Instant.parse(proof.approvalDecision().decidedAt())).isBetween(beforeReview, Instant.now());
        ModelPromotionPolicyPublisherCli.main(new String[] {"publish", "--policy", fixture.policy().toString(),
                "--approval", approval.toString(), "--approval-trust", fixture.approvalTrust().toString(),
                "--trusted-issuers", fixture.issuerTrust().toString(), "--publisher-private-key",
                fixture.publisherPrivate().toString(), "--issuer-private-key", fixture.issuerPrivate().toString(),
                "--dry-run"});

        ModelPromotionPolicyPublisher.Draft draft = mapper.readValue(Files.readString(fixture.policy()),
                ModelPromotionPolicyPublisher.Draft.class);
        ModelPromotionPolicy.Content content = draft.content().approvedContent(
                Instant.parse(proof.approvalDecision().decidedAt()));
        String finalDigest = new ModelPromotionPolicyValidator(mapper).canonicalSha256(content);
        String issuedAt = Instant.now().toString();
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(
                Base64.getDecoder().decode(Files.readString(fixture.issuerPrivate())))));
        signer.update(ModelPromotionPolicySignatureContract.signingBytes(mapper, content,
                "local-issuer", "issuer-key", issuedAt));
        ModelPromotionPolicy signed = new ModelPromotionPolicy(content, finalDigest,
                new ModelPromotionPolicy.PolicyAuthorizationEvidence("local-issuer", "issuer-key",
                        ModelPromotionPolicySignatureContract.encodeSignature(signer.sign()),
                        finalDigest, issuedAt));
        Path signedFile = directory.resolve("signed-policy.json");
        Files.writeString(signedFile, mapper.writeValueAsString(signed));
        String current = capture(() -> ModelPromotionPolicyPublisherCli.main(new String[] {"inspect-policy",
                "--signed-policy", signedFile.toString(), "--trusted-issuers", fixture.issuerTrust().toString()}));
        assertThat(current).contains("\"historicalIntegrity\":\"INTACT_ACTIVE_KEY\"")
                .contains("\"currentAuthorization\":\"VERIFIED\"");

        ModelPromotionTrustedIssuerRegistry.Configuration original = mapper.readValue(
                Files.readString(fixture.issuerTrust()), ModelPromotionTrustedIssuerRegistry.Configuration.class);
        ModelPromotionTrustedIssuerRegistry.ConfiguredKey key = original.keys().getFirst();
        Path retiredRegistry = directory.resolve("retired-issuer-trust.json");
        Files.writeString(retiredRegistry, mapper.writeValueAsString(new ModelPromotionTrustedIssuerRegistry.Configuration(
                original.version(), 2, List.of(new ModelPromotionTrustedIssuerRegistry.ConfiguredKey(
                key.issuerIdentity(), key.keyId(), key.publicKeySpkiBase64(), key.publicKeySha256(),
                key.institutionalScopes(), key.environments(), key.intendedUses(), key.validFrom(), issuedAt,
                ModelPromotionTrustedIssuerRegistry.KeyStatus.RETIRED)))));
        String historical = capture(() -> ModelPromotionPolicyPublisherCli.main(new String[] {"inspect-policy",
                "--signed-policy", signedFile.toString(), "--trusted-issuers", retiredRegistry.toString()}));
        assertThat(historical).contains("\"historicalIntegrity\":\"INTACT_RETIRED_KEY\"")
                .contains("\"currentAuthorization\":\"REJECTED\"");
    }

    @Test
    void independentlySignedRejectionCannotPublish() throws Exception {
        Fixture fixture = fixture();
        Path submission = directory.resolve("rejected-author.json");
        Path approval = directory.resolve("rejection.json");
        ModelPromotionPolicyPublisherCli.main(new String[] {"author", "--policy", fixture.policy().toString(),
                "--approval-trust", fixture.approvalTrust().toString(), "--author-private-key",
                fixture.authorPrivate().toString(), "--author-identity", "local-author-a", "--author-key-id",
                "author-key", "--out", submission.toString()});
        ModelPromotionPolicyPublisherCli.main(new String[] {"approve", "--submission", submission.toString(),
                "--approval-trust", fixture.approvalTrust().toString(), "--approver-private-key",
                fixture.approverPrivate().toString(), "--approver-identity", "local-approver-b",
                "--approver-key-id", "approver-key", "--decision", "REJECTED", "--expires-at",
                Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString(),
                "--out", approval.toString()});
        assertThatThrownBy(() -> ModelPromotionPolicyPublisherCli.main(new String[] {"publish", "--policy",
                fixture.policy().toString(), "--approval", approval.toString(), "--approval-trust",
                fixture.approvalTrust().toString(), "--trusted-issuers", fixture.issuerTrust().toString(),
                "--publisher-private-key", fixture.publisherPrivate().toString(), "--issuer-private-key",
                fixture.issuerPrivate().toString(), "--dry-run"})).isInstanceOf(SecurityException.class);
    }

    private Fixture fixture() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair author = generator.generateKeyPair();
        KeyPair approver = generator.generateKeyPair();
        KeyPair publisher = generator.generateKeyPair();
        KeyPair issuer = generator.generateKeyPair();
        Path authorPrivate = credential("author.key", author);
        Path approverPrivate = credential("approver.key", approver);
        Path publisherPrivate = credential("publisher.key", publisher);
        Path issuerPrivate = credential("issuer.key", issuer);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        ModelGovernanceScope scope = new ModelGovernanceScope(ModelGovernanceScope.CURRENT_INSTITUTION,
                ModelGovernanceEnvironment.DEVELOPMENT);
        List<ModelPromotionPolicy.CriterionRule> rules = Arrays.stream(ModelPromotionCriterion.values())
                .map(criterion -> new ModelPromotionPolicy.CriterionRule(criterion,
                        ModelPromotionPolicy.CriterionApplicability.MANDATORY,
                        criterion.requiredThresholdKeys().stream().collect(Collectors.toMap(k -> k, k -> BigDecimal.ONE)),
                        86400, "VERIFIED", criterion.evidenceSource(), criterion.metricSemantics(),
                        criterion.populationDefinition(), criterion.denominatorDefinition(), null)).toList();
        ModelPromotionPolicyDraft content = new ModelPromotionPolicyDraft(ModelPromotionPolicyDraft.VERSION,
                "local-cli-policy", 1, scope, ModelPromotionIntendedUse.FRAUD_RISK_SCORING,
                "local-author-a", "local-approver-b", "local-review-1", "local-publisher", "publisher-key",
                "local-issuer", "issuer-key",
                now.plus(1, ChronoUnit.DAYS).toString(), now.plus(31, ChronoUnit.DAYS).toString(), rules);
        Path draftContent = directory.resolve("draft-content.json");
        Files.writeString(draftContent, mapper.writeValueAsString(content));
        Path policy = directory.resolve("policy.json");
        ModelPromotionPolicyPublisherCli.main(new String[] {"prepare", "--content", draftContent.toString(),
                "--out", policy.toString()});
        String from = now.minus(1, ChronoUnit.DAYS).toString();
        String until = now.plus(365, ChronoUnit.DAYS).toString();
        Path approvalTrust = directory.resolve("approval-trust.json");
        Files.writeString(approvalTrust, mapper.writeValueAsString(new InternalPolicyApprovalTrustRegistry.Configuration(
                InternalPolicyApprovalTrustRegistry.VERSION, 1, List.of(
                actor("local-author-a", "author-key", author, InternalPolicyApprovalTrustRegistry.Role.POLICY_AUTHOR,
                        from, until),
                actor("local-approver-b", "approver-key", approver,
                        InternalPolicyApprovalTrustRegistry.Role.POLICY_APPROVER, from, until),
                actor("local-publisher", "publisher-key", publisher,
                        InternalPolicyApprovalTrustRegistry.Role.POLICY_PUBLISHER, from, until),
                actor("local-issuer", "issuer-key", issuer,
                        InternalPolicyApprovalTrustRegistry.Role.POLICY_ISSUER, from, until)))));
        byte[] issuerPublic = issuer.getPublic().getEncoded();
        Path issuerTrust = directory.resolve("issuer-trust.json");
        Files.writeString(issuerTrust, mapper.writeValueAsString(new ModelPromotionTrustedIssuerRegistry.Configuration(
                ModelPromotionTrustedIssuerRegistry.VERSION, 1, List.of(
                new ModelPromotionTrustedIssuerRegistry.ConfiguredKey("local-issuer", "issuer-key",
                        Base64.getEncoder().encodeToString(issuerPublic), digest(issuerPublic),
                        List.of(ModelGovernanceScope.CURRENT_INSTITUTION),
                        List.of(ModelGovernanceEnvironment.DEVELOPMENT),
                        List.of(ModelPromotionIntendedUse.FRAUD_RISK_SCORING), from, until,
                        ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE)))));
        return new Fixture(policy, approvalTrust, issuerTrust, authorPrivate, approverPrivate,
                publisherPrivate, issuerPrivate);
    }

    private String capture(ThrowingCommand command) throws Exception {
        PrintStream prior = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes)) {
            System.setOut(output);
            command.run();
        } finally {
            System.setOut(prior);
        }
        return bytes.toString();
    }

    private interface ThrowingCommand {
        void run() throws Exception;
    }

    private InternalPolicyApprovalTrustRegistry.ConfiguredKey actor(String identity, String id, KeyPair pair,
            InternalPolicyApprovalTrustRegistry.Role role, String from, String until) throws Exception {
        byte[] encoded = pair.getPublic().getEncoded();
        return new InternalPolicyApprovalTrustRegistry.ConfiguredKey(identity, id,
                Base64.getEncoder().encodeToString(encoded), digest(encoded), List.of(role),
                List.of(ModelGovernanceScope.CURRENT_INSTITUTION), List.of(ModelGovernanceEnvironment.DEVELOPMENT),
                List.of(ModelPromotionIntendedUse.FRAUD_RISK_SCORING), from, until,
                ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE);
    }

    private String digest(byte[] encoded) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
    }

    private Path credential(String name, KeyPair pair) throws Exception {
        Path path = directory.resolve(name);
        Files.writeString(path, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        return path;
    }

    private record Fixture(Path policy, Path approvalTrust, Path issuerTrust, Path authorPrivate,
            Path approverPrivate, Path publisherPrivate, Path issuerPrivate) {
    }
}
