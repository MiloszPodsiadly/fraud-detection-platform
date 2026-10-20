package com.frauddetection.alert.governance.modelpromotion;

import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.math.BigDecimal;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Offline first-party author, independent review and publication commands. */
public final class ModelPromotionPolicyPublisherCli {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final Set<String> VALUE_OPTIONS = Set.of("--policy", "--content", "--approval", "--submission",
            "--approval-trust", "--trusted-issuers", "--author-private-key", "--approver-private-key",
            "--publisher-private-key", "--issuer-private-key", "--author-identity", "--author-key-id",
            "--approver-identity", "--approver-key-id", "--publisher-identity", "--publisher-key-id",
            "--issuer", "--key-id", "--decision", "--expires-at", "--policy-id", "--review-at",
            "--approval-reference", "--out", "--signed-policy");

    private ModelPromotionPolicyPublisherCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("First argument must be help, prepare, demo-draft, author, approve, publish, trust-check or inspect-policy");
        }
        Map<String, String> options = options(Arrays.copyOfRange(args, 1, args.length));
        ObjectMapper mapper = new ObjectMapper();
        switch (args[0]) {
            case "help" -> help();
            case "prepare" -> prepare(mapper, options);
            case "demo-draft" -> demoDraft(mapper, options);
            case "author" -> author(mapper, options);
            case "approve" -> approve(mapper, options);
            case "publish" -> publish(mapper, options);
            case "trust-check" -> trustCheck(mapper, options);
            case "inspect-policy" -> inspectPolicy(mapper, options);
            default -> throw new IllegalArgumentException("Unknown workflow command: " + args[0]);
        }
    }

    private static void help() {
        System.out.println("Commands: prepare, demo-draft, author, approve, publish, trust-check, inspect-policy");
        System.out.println("trust-check --approval-trust <public-registry.json> --trusted-issuers <public-registry.json>");
        System.out.println("inspect-policy --signed-policy <signed-policy.json> --trusted-issuers <public-registry.json>");
        System.out.println("Both commands enforce MODEL_PROMOTION_APPROVAL_TRUST_MIN_GENERATION / MODEL_PROMOTION_TRUST_MIN_GENERATION when applicable.");
        System.out.println("Historical INTACT never grants current VERIFIED authorization. See docs/operations/model_promotion_trust_operations.md.");
    }

    private static void trustCheck(ObjectMapper mapper, Map<String, String> options) throws Exception {
        InternalPolicyApprovalTrustRegistry approval = approvalTrust(mapper, options);
        Path issuersPath = Path.of(required(options, "--trusted-issuers"));
        ModelPromotionTrustedIssuerRegistry issuers = ModelPromotionTrustedIssuerRegistry.load(mapper, issuersPath,
                generationFloor("MODEL_PROMOTION_TRUST_MIN_GENERATION"));
        String issuerFileSha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(issuersPath)));
        System.out.println(mapper.writeValueAsString(Map.of(
                "approvalGeneration", approval.generation(),
                "approvalRegistryCanonicalSha256", approval.canonicalSha256(),
                "issuerGeneration", issuers.generation(),
                "issuerRegistryFileSha256", issuerFileSha256)));
    }

    private static void inspectPolicy(ObjectMapper mapper, Map<String, String> options) throws Exception {
        ModelPromotionPolicy policy = read(mapper, options, "--signed-policy", ModelPromotionPolicy.class);
        ModelPromotionTrustedIssuerRegistry issuers = ModelPromotionTrustedIssuerRegistry.load(mapper,
                Path.of(required(options, "--trusted-issuers")),
                generationFloor("MODEL_PROMOTION_TRUST_MIN_GENERATION"));
        Ed25519ModelPromotionPolicyAuthorityVerifier verifier =
                new Ed25519ModelPromotionPolicyAuthorityVerifier(mapper, issuers, CLOCK);
        ModelPromotionPolicyHistoricalIntegrity historical = verifier.inspectHistoricalIntegrity(policy);
        ModelPromotionPolicyAuthorityVerification current = verifier.verify(policy);
        System.out.println(mapper.writeValueAsString(Map.of(
                "policyId", policy.content().policyId(),
                "revision", policy.content().revision(),
                "canonicalSha256", policy.canonicalSha256(),
                "historicalIntegrity", historical.status().name(),
                "historicalReason", historical.reason().name(),
                "currentAuthorization", current.status().name(),
                "currentReason", current.reason().name())));
    }

    private static void prepare(ObjectMapper mapper, Map<String, String> options) throws Exception {
        ModelPromotionPolicy.Content content = read(mapper, options, "--content", ModelPromotionPolicy.Content.class);
        String sha256 = new ModelPromotionPolicyValidator(mapper).canonicalSha256(content);
        new ModelPromotionPolicyValidator(mapper).validateUnsignedForPublication(content, sha256);
        write(mapper, options, new ModelPromotionPolicyPublisher.UnsignedPolicy(content, sha256));
        System.out.println("Prepared policy SHA-256 " + sha256);
    }

    private static void demoDraft(ObjectMapper mapper, Map<String, String> options) throws Exception {
        Instant reviewAt = Instant.parse(required(options, "--review-at"));
        if (!reviewAt.equals(reviewAt.truncatedTo(ChronoUnit.SECONDS))
                || !reviewAt.toString().equals(required(options, "--review-at"))
                || !reviewAt.isAfter(CLOCK.instant().plusSeconds(30))) {
            throw new IllegalArgumentException("--review-at must be canonical UTC and at least 30 seconds in the future");
        }
        List<ModelPromotionPolicy.CriterionRule> rules = Arrays.stream(ModelPromotionCriterion.values())
                .map(criterion -> new ModelPromotionPolicy.CriterionRule(criterion,
                        ModelPromotionPolicy.CriterionApplicability.MANDATORY,
                        criterion.requiredThresholdKeys().stream().collect(Collectors.toMap(k -> k, k -> BigDecimal.ONE)),
                        86400, "VERIFIED", criterion.evidenceSource(), criterion.metricSemantics(),
                        criterion.populationDefinition(), criterion.denominatorDefinition(), null)).toList();
        ModelPromotionPolicy.Content content = new ModelPromotionPolicy.Content(ModelPromotionPolicy.CURRENT_VERSION,
                required(options, "--policy-id"), 1,
                new ModelGovernanceScope(ModelGovernanceScope.CURRENT_INSTITUTION,
                        ModelGovernanceEnvironment.DEVELOPMENT), ModelPromotionIntendedUse.FRAUD_RISK_SCORING,
                new ModelPromotionPolicy.PolicyProvenance(ModelPromotionPolicy.PolicyAuthority.INSTITUTIONAL_APPROVED,
                        "local-dev-policy-author-a", "local-dev-policy-approver-b", reviewAt.toString(),
                        required(options, "--approval-reference")),
                reviewAt.plus(1, ChronoUnit.DAYS).toString(), reviewAt.plus(31, ChronoUnit.DAYS).toString(), rules);
        String sha256 = new ModelPromotionPolicyValidator(mapper).canonicalSha256(content);
        new ModelPromotionPolicyValidator(mapper).validateUnsignedForPublication(content, sha256);
        write(mapper, options, new ModelPromotionPolicyPublisher.UnsignedPolicy(content, sha256));
        System.out.println("Prepared local demo policy SHA-256 " + sha256 + "; no approval was created");
    }

    private static void author(ObjectMapper mapper, Map<String, String> options) throws Exception {
        ModelPromotionPolicyPublisher.UnsignedPolicy policy = read(mapper, options, "--policy",
                ModelPromotionPolicyPublisher.UnsignedPolicy.class);
        new ModelPromotionPolicyValidator(mapper).validateUnsignedForPublication(
                policy.content(), policy.canonicalSha256());
        InternalPolicyApprovalTrustRegistry trust = approvalTrust(mapper, options);
        String identity = required(options, "--author-identity");
        String keyId = required(options, "--author-key-id");
        PrivateKey key = privateKey(options, "--author-private-key");
        Instant now = CLOCK.instant().truncatedTo(ChronoUnit.SECONDS);
        requireCredential(trust, identity, keyId, InternalPolicyApprovalTrustRegistry.Role.POLICY_AUTHOR,
                policy.content(), key, now);
        InternalPolicyApprovalArtifact.AuthorStatement statement = new InternalPolicyApprovalArtifact.AuthorStatement(
                InternalPolicyApprovalArtifact.VERSION, InternalPolicyApprovalArtifact.AUTHOR_PURPOSE,
                policy.content().policyId(), policy.content().revision(), policy.canonicalSha256(),
                policy.content().governanceScope(), policy.content().intendedUse(),
                policy.content().effectiveFromInclusive(), policy.content().effectiveToExclusive(),
                identity, keyId, required(options, "--publisher-identity"),
                required(options, "--publisher-key-id"), required(options, "--issuer"),
                required(options, "--key-id"), now.toString());
        if (!identity.equals(policy.content().provenance().authorIdentity())) {
            throw new SecurityException("Author credential does not match policy provenance");
        }
        String signature = sign(key, InternalPolicyApprovalArtifact.authorSigningBytes(mapper, statement));
        write(mapper, options, new InternalPolicyAuthorSubmission(policy, statement, signature));
        System.out.println("Author signed policy SHA-256 " + policy.canonicalSha256());
    }

    private static void approve(ObjectMapper mapper, Map<String, String> options) throws Exception {
        InternalPolicyAuthorSubmission submission = read(mapper, options, "--submission",
                InternalPolicyAuthorSubmission.class);
        ModelPromotionPolicyPublisher.UnsignedPolicy policy = submission.policy();
        new ModelPromotionPolicyValidator(mapper).validateUnsignedForPublication(
                policy.content(), policy.canonicalSha256());
        InternalPolicyApprovalArtifact.AuthorStatement author = submission.authorStatement();
        if (!author.policyCanonicalSha256().equals(policy.canonicalSha256())
                || !author.policyId().equals(policy.content().policyId())
                || author.revision() != policy.content().revision()
                || !author.authorIdentity().equals(policy.content().provenance().authorIdentity())
                || !author.governanceScope().equals(policy.content().governanceScope())
                || author.intendedUse() != policy.content().intendedUse()
                || !author.effectiveFromInclusive().equals(policy.content().effectiveFromInclusive())
                || !author.effectiveToExclusive().equals(policy.content().effectiveToExclusive())) {
            throw new SecurityException("Author statement does not match the reviewed policy");
        }
        InternalPolicyApprovalTrustRegistry trust = approvalTrust(mapper, options);
        Instant authored = Instant.parse(author.authoredAt());
        requireSignedActor(trust, author.authorIdentity(), author.authorKeyId(),
                InternalPolicyApprovalTrustRegistry.Role.POLICY_AUTHOR, policy.content(), authored,
                InternalPolicyApprovalArtifact.authorSigningBytes(mapper, author), submission.authorSignature());
        String identity = required(options, "--approver-identity");
        String keyId = required(options, "--approver-key-id");
        PrivateKey key = privateKey(options, "--approver-private-key");
        if (identity.equals(author.authorIdentity())
                || !identity.equals(policy.content().provenance().approvingAuthority())) {
            throw new SecurityException("Review must use the independent named approver");
        }
        InternalPolicyApprovalTrustRegistry.TrustedKey authorKey = trust.find(author.authorIdentity(),
                author.authorKeyId());
        InternalPolicyApprovalTrustRegistry.TrustedKey approverKey = requireCredential(trust, identity, keyId,
                InternalPolicyApprovalTrustRegistry.Role.POLICY_APPROVER, policy.content(), key, CLOCK.instant());
        if (authorKey.publicKeySha256().equals(approverKey.publicKeySha256())) {
            throw new SecurityException("Author and approver may not share a key");
        }
        InternalPolicyApprovalTrustRegistry.TrustedKey issuerKey = requireMandate(trust,
                author.issuerIdentity(), author.issuerKeyId(),
                InternalPolicyApprovalTrustRegistry.Role.POLICY_ISSUER, policy.content(), CLOCK.instant());
        if (identity.equals(author.issuerIdentity())
                || approverKey.publicKeySha256().equals(issuerKey.publicKeySha256())) {
            throw new SecurityException("Approver and issuer must be independent");
        }
        InternalPolicyApprovalArtifact.Decision decision = InternalPolicyApprovalArtifact.Decision.valueOf(
                required(options, "--decision"));
        String decidedAt = decision == InternalPolicyApprovalArtifact.Decision.APPROVED
                ? policy.content().provenance().approvedAt()
                : CLOCK.instant().truncatedTo(ChronoUnit.SECONDS).toString();
        Instant decisionTime = Instant.parse(decidedAt);
        if (decisionTime.isBefore(authored) || decisionTime.isAfter(CLOCK.instant())) {
            throw new SecurityException("Predeclared policy approval time is outside the review window");
        }
        String expiresAt = required(options, "--expires-at");
        if (!Instant.parse(expiresAt).isAfter(CLOCK.instant())
                || !Instant.parse(expiresAt).isAfter(decisionTime)) {
            throw new SecurityException("Review expiration must follow the decision and remain in the future");
        }
        InternalPolicyApprovalArtifact.ApprovalDecision review = new InternalPolicyApprovalArtifact.ApprovalDecision(
                InternalPolicyApprovalArtifact.VERSION, InternalPolicyApprovalArtifact.APPROVER_PURPOSE,
                InternalPolicyApprovalArtifact.authorStatementSha256(mapper, author), identity, keyId, decision,
                decidedAt, expiresAt, policy.content().provenance().approvalReference());
        InternalPolicyApprovalArtifact proof = new InternalPolicyApprovalArtifact(author,
                submission.authorSignature(), review,
                sign(key, InternalPolicyApprovalArtifact.approverSigningBytes(mapper, review)));
        write(mapper, options, proof);
        System.out.println("Recorded " + decision + " for policy SHA-256 " + policy.canonicalSha256()
                + "; proof SHA-256 " + InternalPolicyApprovalArtifact.proofSha256(mapper, proof));
    }

    private static void publish(ObjectMapper mapper, Map<String, String> options) throws Exception {
        ModelPromotionPolicyPublisher.UnsignedPolicy policy = read(mapper, options, "--policy",
                ModelPromotionPolicyPublisher.UnsignedPolicy.class);
        InternalPolicyApprovalArtifact proof = read(mapper, options, "--approval",
                InternalPolicyApprovalArtifact.class);
        InternalPolicyApprovalTrustRegistry trust = approvalTrust(mapper, options);
        PrivateKey publisherKey = privateKey(options, "--publisher-private-key");
        PrivateKey issuerKey = privateKey(options, "--issuer-private-key");
        InternalPolicyPublicationAuthorization authorization = new InternalPolicyPublicationAuthorization(
                mapper, trust, proof, publisherKey, CLOCK);
        String issuer = proof.authorStatement().issuerIdentity();
        String keyId = proof.authorStatement().issuerKeyId();
        ModelPromotionTrustedIssuerRegistry issuers = ModelPromotionTrustedIssuerRegistry.load(mapper,
                Path.of(required(options, "--trusted-issuers")), generationFloor("MODEL_PROMOTION_TRUST_MIN_GENERATION"));
        ModelPromotionPolicyPublisher publisher = new ModelPromotionPolicyPublisher(mapper,
                new ModelPromotionPolicyValidator(mapper), authorization,
                new Ed25519ModelPromotionPolicyAuthorityVerifier(mapper, issuers, CLOCK), null, CLOCK);
        ModelPromotionPolicyPublisher.PreparedPublication prepared = publisher.prepare(policy, issuerKey, issuer, keyId);
        if (options.containsKey("--dry-run")) {
            System.out.println(mapper.writeValueAsString(Map.of("validated", true, "published", false,
                    "policyCanonicalSha256", policy.canonicalSha256(),
                    "approvalProofSha256", InternalPolicyApprovalArtifact.proofSha256(mapper, proof))));
            return;
        }
        String mongoUri = System.getenv("MODEL_PROMOTION_PUBLISH_MONGODB_URI");
        if (mongoUri == null || mongoUri.isBlank()) {
            throw new IllegalStateException("MODEL_PROMOTION_PUBLISH_MONGODB_URI is required for publication");
        }
        SimpleMongoClientDatabaseFactory factory = new SimpleMongoClientDatabaseFactory(mongoUri);
        try {
            MongoModelPromotionPolicyPublicationStore store = new MongoModelPromotionPolicyPublicationStore(
                    new MongoTemplate(factory), new TransactionTemplate(new MongoTransactionManager(factory)));
            System.out.println(mapper.writeValueAsString(store.publish(prepared.policy(), CLOCK.instant(),
                    prepared.approvalArtifact(), prepared.authorization())));
        } finally {
            factory.destroy();
        }
    }

    private static InternalPolicyApprovalTrustRegistry approvalTrust(ObjectMapper mapper, Map<String, String> options) {
        return InternalPolicyApprovalTrustRegistry.load(mapper, Path.of(required(options, "--approval-trust")),
                generationFloor("MODEL_PROMOTION_APPROVAL_TRUST_MIN_GENERATION"));
    }

    private static long generationFloor(String environment) {
        return Long.parseLong(System.getenv().getOrDefault(environment, "1"));
    }

    private static InternalPolicyApprovalTrustRegistry.TrustedKey requireCredential(
            InternalPolicyApprovalTrustRegistry trust, String identity, String keyId,
            InternalPolicyApprovalTrustRegistry.Role role, ModelPromotionPolicy.Content content,
            PrivateKey credential, Instant at) throws Exception {
        InternalPolicyApprovalTrustRegistry.TrustedKey key = requireMandate(trust, identity, keyId, role, content, at);
        byte[] challenge = new byte[32];
        new SecureRandom().nextBytes(challenge);
        if (!verify(key.publicKey(), challenge, sign(credential, challenge))) {
            throw new SecurityException("Private credential does not match the mandated public key");
        }
        return key;
    }

    private static void requireSignedActor(InternalPolicyApprovalTrustRegistry trust, String identity, String keyId,
            InternalPolicyApprovalTrustRegistry.Role role, ModelPromotionPolicy.Content content, Instant at,
            byte[] statement, String signature) throws Exception {
        InternalPolicyApprovalTrustRegistry.TrustedKey key = requireMandate(trust, identity, keyId, role, content, at);
        if (!verify(key.publicKey(), statement, signature)) {
            throw new SecurityException("Signed author statement is invalid");
        }
    }

    private static InternalPolicyApprovalTrustRegistry.TrustedKey requireMandate(
            InternalPolicyApprovalTrustRegistry trust, String identity, String keyId,
            InternalPolicyApprovalTrustRegistry.Role role, ModelPromotionPolicy.Content content, Instant at) {
        InternalPolicyApprovalTrustRegistry.TrustedKey key = trust.find(identity, keyId);
        Instant now = CLOCK.instant();
        if (key == null || key.status() != ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE
                || !key.roles().contains(role)
                || !key.institutionalScopes().contains(content.governanceScope().institutionalScope())
                || !key.environments().contains(content.governanceScope().environment())
                || !key.intendedUses().contains(content.intendedUse())
                || at.isBefore(key.validFrom()) || !at.isBefore(key.validUntil())
                || now.isBefore(key.validFrom()) || !now.isBefore(key.validUntil())) {
            throw new SecurityException("Actor key has no active mandate for this policy");
        }
        return key;
    }

    private static boolean verify(PublicKey key, byte[] bytes, String signature) throws Exception {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(bytes);
        return verifier.verify(ModelPromotionPolicySignatureContract.decodeSignature(signature));
    }

    private static String sign(PrivateKey key, byte[] bytes) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(bytes);
        return ModelPromotionPolicySignatureContract.encodeSignature(signer.sign());
    }

    private static PrivateKey privateKey(Map<String, String> options, String name) throws Exception {
        byte[] der = Base64.getDecoder().decode(Files.readString(Path.of(required(options, name))).trim());
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static <T> T read(ObjectMapper mapper, Map<String, String> options, String name, Class<T> type)
            throws Exception {
        return mapper.readValue(Files.readString(Path.of(required(options, name))), type);
    }

    private static void write(ObjectMapper mapper, Map<String, String> options, Object value) throws Exception {
        Files.writeString(Path.of(required(options, "--out")), mapper.writeValueAsString(value) + "\n",
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> result = new HashMap<>();
        for (int index = 0; index < args.length; index++) {
            String option = args[index];
            if ("--dry-run".equals(option)) {
                if (result.putIfAbsent(option, "true") != null) throw new IllegalArgumentException("Duplicate " + option);
            } else if (VALUE_OPTIONS.contains(option) && index + 1 < args.length) {
                if (result.putIfAbsent(option, args[++index]) != null) throw new IllegalArgumentException("Duplicate " + option);
            } else {
                throw new IllegalArgumentException("Unsupported or incomplete option: " + option);
            }
        }
        return result;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
