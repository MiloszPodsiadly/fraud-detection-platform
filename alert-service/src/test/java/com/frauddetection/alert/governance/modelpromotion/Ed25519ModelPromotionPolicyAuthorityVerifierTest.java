package com.frauddetection.alert.governance.modelpromotion;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Ed25519ModelPromotionPolicyAuthorityVerifierTest {

    private static final String ISSUER = "institutional-policy-issuer";
    private static final String KEY_ID = "policy-key-1";
    private static final String ISSUED_AT = "2025-12-31T12:00:00Z";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void verifiesOnlyTheCorrectSignatureFromTheAuthorizedIssuerAndKey() throws Exception {
        KeyPair pair = keyPair();
        ModelPromotionPolicy policy = signedPolicy(content(), pair, ISSUER, KEY_ID, ISSUED_AT);

        assertThat(verifier(pair).verify(policy).status())
                .isEqualTo(ModelPromotionPolicyAuthorityVerification.Status.VERIFIED);
        assertRejected(verifier(keyPair()).verify(policy));
        assertRejected(verifier(pair).verify(signedPolicy(content(), pair, "unknown-issuer", KEY_ID, ISSUED_AT)));
        assertRejected(verifier(pair).verify(signedPolicy(content(), pair, ISSUER, "unknown-key", ISSUED_AT)));
        assertRejected(verifier(pair).verify(ModelPromotionAssessmentTestFixtures.policy()));
    }

    @Test
    void rejectsWrongPurposeTamperedSignatureAndMalformedEncoding() throws Exception {
        KeyPair pair = keyPair();
        ModelPromotionPolicy original = signedPolicy(content(), pair, ISSUER, KEY_ID, ISSUED_AT);
        byte[] input = ModelPromotionPolicySignatureContract.verificationBytes(mapper, original);
        byte[] otherPurpose = new String(input, StandardCharsets.UTF_8)
                .replace(ModelPromotionPolicySignatureContract.PURPOSE, "AUDIT_ANCHOR")
                .getBytes(StandardCharsets.UTF_8);
        ModelPromotionPolicy wrongPurpose = withSignature(original, sign(pair, otherPurpose));
        byte[] altered = ModelPromotionPolicySignatureContract.decodeSignature(original.authorizationEvidence().signature());
        altered[0] ^= 1;

        assertRejected(verifier(pair).verify(wrongPurpose));
        assertRejected(verifier(pair).verify(withSignature(original, altered)));
        assertRejected(verifier(pair).verify(withEncodedSignature(original, "not-base64!")));
        assertRejected(verifier(pair).verify(withEncodedSignature(original,
                original.authorizationEvidence().signature() + "=")));
    }

    @Test
    void rejectsChangedPolicyContentEvenWhenTheAttackerRehashesIt() throws Exception {
        KeyPair pair = keyPair();
        ModelPromotionPolicy original = signedPolicy(content(), pair, ISSUER, KEY_ID, ISSUED_AT);
        ModelPromotionPolicy.Content source = original.content();
        List<ModelPromotionPolicy.Content> changes = new ArrayList<>();
        changes.add(copy(source, 2, source.governanceScope(), source.provenance(), source.criteria()));
        changes.add(copy(source, 1, new ModelGovernanceScope(ModelGovernanceScope.CURRENT_INSTITUTION,
                ModelGovernanceEnvironment.TEST), source.provenance(), source.criteria()));
        changes.add(copy(source, 1, source.governanceScope(), new ModelPromotionPolicy.PolicyProvenance(
                ModelPromotionPolicy.PolicyAuthority.INSTITUTIONAL_APPROVED, "policy-author", "risk-committee",
                "2025-12-31T00:00:00Z", "other-approval"), source.criteria()));
        changes.add(copy(source, 1, source.governanceScope(), source.provenance(), changedThreshold(source.criteria())));

        for (ModelPromotionPolicy.Content changed : changes) {
            String digest = new ModelPromotionPolicyValidator(mapper).canonicalSha256(changed);
            ModelPromotionPolicy tampered = new ModelPromotionPolicy(changed, digest,
                    new ModelPromotionPolicy.PolicyAuthorizationEvidence(ISSUER, KEY_ID,
                            original.authorizationEvidence().signature(), digest, ISSUED_AT));
            assertRejected(verifier(pair).verify(tampered));
        }
    }

    @Test
    void rejectsUnauthorizedPermissionsChronologyAndKeyLifecycle() throws Exception {
        KeyPair pair = keyPair();
        ModelPromotionPolicy policy = signedPolicy(content(), pair, ISSUER, KEY_ID, ISSUED_AT);
        assertRejected(verifier(pair, List.of(ModelGovernanceEnvironment.TEST),
                ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE, "2025-01-01T00:00:00Z",
                "2027-01-01T00:00:00Z").verify(policy));
        assertRejected(verifier(pair, List.of(ModelGovernanceEnvironment.PRODUCTION),
                ModelPromotionTrustedIssuerRegistry.KeyStatus.REVOKED, "2025-01-01T00:00:00Z",
                "2027-01-01T00:00:00Z").verify(policy));
        assertRejected(verifier(pair, List.of(ModelGovernanceEnvironment.PRODUCTION),
                ModelPromotionTrustedIssuerRegistry.KeyStatus.RETIRED, "2025-01-01T00:00:00Z",
                "2027-01-01T00:00:00Z").verify(policy));
        assertRejected(verifier(pair, List.of(ModelGovernanceEnvironment.PRODUCTION),
                ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE, "2025-01-01T00:00:00Z",
                "2026-02-01T00:00:00Z").verify(policy));
        assertRejected(verifier(pair, List.of(ModelGovernanceEnvironment.PRODUCTION),
                ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE, "2026-01-01T00:00:00Z",
                "2027-01-01T00:00:00Z").verify(policy));
        ModelPromotionPolicy.PolicyAuthorizationEvidence source = policy.authorizationEvidence();
        assertRejected(verifier(pair).verify(new ModelPromotionPolicy(policy.content(), policy.canonicalSha256(),
                new ModelPromotionPolicy.PolicyAuthorizationEvidence(ISSUER, KEY_ID, source.signature(),
                        source.policyCanonicalSha256(), "2025-12-31T12:00:00.0Z"))));
        assertRejected(verifier(pair).verify(new ModelPromotionPolicy(policy.content(), policy.canonicalSha256(),
                new ModelPromotionPolicy.PolicyAuthorizationEvidence(ISSUER, KEY_ID, source.signature(),
                        source.policyCanonicalSha256(), "2025-12-31T13:00:00Z"))));
        ModelPromotionPolicy.Content invalidInterval = new ModelPromotionPolicy.Content(policy.content().policyVersion(),
                policy.content().policyId(), policy.content().revision(), policy.content().governanceScope(),
                policy.content().intendedUse(), policy.content().provenance(),
                policy.content().effectiveFromInclusive(), "2025-12-31T00:00:00Z", policy.content().criteria());
        assertRejected(verifier(pair).verify(signedPolicy(invalidInterval, pair, ISSUER, KEY_ID, ISSUED_AT)));
    }

    @Test
    void registryRejectsMalformedAndContradictoryTrustMaterial() throws Exception {
        KeyPair pair = keyPair();
        ModelPromotionTrustedIssuerRegistry.ConfiguredKey valid = configuredKey(pair);
        assertThatThrownBy(() -> registry(List.of(valid, valid))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry(List.of(valid, new ModelPromotionTrustedIssuerRegistry.ConfiguredKey(
                ISSUER, "alias-key", valid.publicKeySpkiBase64(), valid.publicKeySha256(),
                valid.institutionalScopes(), valid.environments(), valid.intendedUses(),
                valid.validFrom(), valid.validUntil(), ModelPromotionTrustedIssuerRegistry.KeyStatus.REVOKED))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("public keys must be globally unique");
        assertThatThrownBy(() -> registry(List.of(valid, new ModelPromotionTrustedIssuerRegistry.ConfiguredKey(
                "other-institutional-issuer", KEY_ID, valid.publicKeySpkiBase64(), valid.publicKeySha256(),
                valid.institutionalScopes(), valid.environments(), valid.intendedUses(),
                valid.validFrom(), valid.validUntil(), valid.status()))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry(List.of(withPublicKey(valid, "!!!"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry(List.of(withPublicKey(valid, null))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry(List.of(withPublicKey(valid,
                Base64.getEncoder().encodeToString(new byte[32])))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry(List.of(new ModelPromotionTrustedIssuerRegistry.ConfiguredKey(
                ISSUER, KEY_ID, valid.publicKeySpkiBase64(), "0".repeat(64), valid.institutionalScopes(),
                valid.environments(), valid.intendedUses(), valid.validFrom(), valid.validUntil(), valid.status()))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry(List.of(new ModelPromotionTrustedIssuerRegistry.ConfiguredKey(
                ISSUER, KEY_ID, valid.publicKeySpkiBase64(), valid.publicKeySha256(), List.of(),
                valid.environments(), valid.intendedUses(), valid.validFrom(), valid.validUntil(), valid.status()))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry(List.of(new ModelPromotionTrustedIssuerRegistry.ConfiguredKey(
                ISSUER, KEY_ID, valid.publicKeySpkiBase64(), valid.publicKeySha256(), valid.institutionalScopes(),
                List.of(), valid.intendedUses(), valid.validFrom(), valid.validUntil(), valid.status()))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry(List.of(new ModelPromotionTrustedIssuerRegistry.ConfiguredKey(
                ISSUER, KEY_ID, valid.publicKeySpkiBase64(), valid.publicKeySha256(), valid.institutionalScopes(),
                valid.environments(), List.of(), valid.validFrom(), valid.validUntil(), valid.status()))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry(List.of(new ModelPromotionTrustedIssuerRegistry.ConfiguredKey(
                ISSUER, KEY_ID, valid.publicKeySpkiBase64(), valid.publicKeySha256(), valid.institutionalScopes(),
                valid.environments(), valid.intendedUses(), valid.validUntil(), valid.validFrom(), valid.status()))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rotationRetirementAndRevocationSeparateHistoryFromNewAuthorization() throws Exception {
        KeyPair oldPair = keyPair();
        KeyPair newPair = keyPair();
        String newKeyId = "policy-key-2";
        ModelPromotionPolicy oldPolicy = signedPolicy(content(), oldPair, ISSUER, KEY_ID, ISSUED_AT);
        ModelPromotionPolicy newPolicy = signedPolicy(content(), newPair, ISSUER, newKeyId, ISSUED_AT);
        ModelPromotionTrustedIssuerRegistry.ConfiguredKey retired = key(oldPair, ISSUER, KEY_ID,
                ModelPromotionTrustedIssuerRegistry.KeyStatus.RETIRED, "2025-01-01T00:00:00Z",
                "2026-02-01T00:00:00Z");
        ModelPromotionTrustedIssuerRegistry.ConfiguredKey active = key(newPair, ISSUER, newKeyId,
                ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE, "2025-01-01T00:00:00Z",
                "2027-01-01T00:00:00Z");
        ModelPromotionTrustedIssuerRegistry rotatedRegistry = registry(List.of(retired, active), 2, 2);
        Ed25519ModelPromotionPolicyAuthorityVerifier verifier = new Ed25519ModelPromotionPolicyAuthorityVerifier(
                mapper, rotatedRegistry, CLOCK);

        assertThat(verifier.verify(oldPolicy).reason())
                .isEqualTo(ModelPromotionPolicyAuthorityVerification.Reason.POLICY_KEY_RETIRED);
        assertThat(verifier.inspectHistoricalIntegrity(oldPolicy).status())
                .isEqualTo(ModelPromotionPolicyHistoricalIntegrity.Status.INTACT_RETIRED_KEY);
        assertThat(verifier.verify(newPolicy).status())
                .isEqualTo(ModelPromotionPolicyAuthorityVerification.Status.VERIFIED);
        assertThat(new Ed25519ModelPromotionPolicyAuthorityVerifier(mapper, rotatedRegistry, CLOCK)
                .verify(newPolicy).status()).isEqualTo(ModelPromotionPolicyAuthorityVerification.Status.VERIFIED);

        ModelPromotionPolicy.Content futureContent = withPeriod(content(), "2026-04-01T00:00:00Z",
                "2027-01-01T00:00:00Z");
        ModelPromotionPolicy newSignatureFromRetiredKey = signedPolicy(futureContent, oldPair, ISSUER, KEY_ID,
                "2026-03-01T00:00:00Z");
        assertThat(verifier.inspectHistoricalIntegrity(newSignatureFromRetiredKey).status())
                .isEqualTo(ModelPromotionPolicyHistoricalIntegrity.Status.INVALID);
        assertThat(verifier.verify(newSignatureFromRetiredKey).reason())
                .isEqualTo(ModelPromotionPolicyAuthorityVerification.Reason.POLICY_KEY_RETIRED);

        ModelPromotionTrustedIssuerRegistry.ConfiguredKey revoked = key(oldPair, ISSUER, KEY_ID,
                ModelPromotionTrustedIssuerRegistry.KeyStatus.REVOKED, "2025-01-01T00:00:00Z",
                "2026-02-01T00:00:00Z");
        Ed25519ModelPromotionPolicyAuthorityVerifier afterRevocation = new Ed25519ModelPromotionPolicyAuthorityVerifier(
                mapper, registry(List.of(revoked, active), 3, 3), CLOCK);
        assertThat(afterRevocation.verify(oldPolicy).reason())
                .isEqualTo(ModelPromotionPolicyAuthorityVerification.Reason.POLICY_KEY_REVOKED);
        assertThat(afterRevocation.inspectHistoricalIntegrity(oldPolicy).status())
                .isEqualTo(ModelPromotionPolicyHistoricalIntegrity.Status.INTACT_REVOKED_KEY);
        assertThat(afterRevocation.verify(newPolicy).status())
                .isEqualTo(ModelPromotionPolicyAuthorityVerification.Status.VERIFIED);
    }

    @Test
    void replayedRegistryGenerationAndIssuerOrKeySubstitutionFailClosed() throws Exception {
        KeyPair pair = keyPair();
        ModelPromotionTrustedIssuerRegistry.ConfiguredKey entry = configuredKey(pair);
        assertThatThrownBy(() -> registry(List.of(entry), 1, 2)).isInstanceOf(IllegalStateException.class);
        Ed25519ModelPromotionPolicyAuthorityVerifier verifier = new Ed25519ModelPromotionPolicyAuthorityVerifier(
                mapper, registry(List.of(entry), 2, 2), CLOCK);
        ModelPromotionPolicy policy = signedPolicy(content(), pair, ISSUER, KEY_ID, ISSUED_AT);
        assertThat(verifier.verify(policy).status())
                .isEqualTo(ModelPromotionPolicyAuthorityVerification.Status.VERIFIED);
        assertThat(verifier.verify(signedPolicy(content(), pair, "other-issuer", KEY_ID, ISSUED_AT)).reason())
                .isEqualTo(ModelPromotionPolicyAuthorityVerification.Reason.POLICY_ISSUER_UNAUTHORIZED);
        assertThat(verifier.verify(signedPolicy(content(), pair, ISSUER, "other-key", ISSUED_AT)).reason())
                .isEqualTo(ModelPromotionPolicyAuthorityVerification.Reason.POLICY_KEY_UNKNOWN);
    }

    @Test
    void futureIssuedAndExpiredPoliciesCannotAuthorizeButHistoryCanRemainIntact() throws Exception {
        KeyPair pair = keyPair();
        Ed25519ModelPromotionPolicyAuthorityVerifier verifier = verifier(pair);
        ModelPromotionPolicy.Content future = withPeriod(content(), "2026-07-01T00:00:00Z",
                "2027-01-01T00:00:00Z");
        ModelPromotionPolicy tooFarInFuture = signedPolicy(future, pair, ISSUER, KEY_ID,
                "2026-06-01T00:00:31Z");
        ModelPromotionPolicy withinSkew = signedPolicy(future, pair, ISSUER, KEY_ID,
                "2026-06-01T00:00:30Z");
        assertThat(verifier.verify(tooFarInFuture).reason())
                .isEqualTo(ModelPromotionPolicyAuthorityVerification.Reason.POLICY_SIGNATURE_FUTURE_ISSUED);
        assertThat(verifier.verify(withinSkew).status())
                .isEqualTo(ModelPromotionPolicyAuthorityVerification.Status.VERIFIED);
        ModelPromotionPolicy expired = signedPolicy(withPeriod(content(), "2026-01-01T00:00:00Z",
                "2026-02-01T00:00:00Z"), pair, ISSUER, KEY_ID, ISSUED_AT);
        assertThat(verifier.verify(expired).reason())
                .isEqualTo(ModelPromotionPolicyAuthorityVerification.Reason.POLICY_EXPIRED);
        assertThat(verifier.inspectHistoricalIntegrity(expired).status())
                .isEqualTo(ModelPromotionPolicyHistoricalIntegrity.Status.INTACT_ACTIVE_KEY);
    }

    @Test
    void boundedReasonMetricsDoNotExposeIssuerOrKeyIdentifiers() throws Exception {
        KeyPair pair = keyPair();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            Ed25519ModelPromotionPolicyAuthorityVerifier verifier = new Ed25519ModelPromotionPolicyAuthorityVerifier(
                    mapper, registry(List.of(configuredKey(pair))), CLOCK, meters);
            ModelPromotionPolicy valid = signedPolicy(content(), pair, ISSUER, KEY_ID, ISSUED_AT);
            ModelPromotionPolicy unknownKey = signedPolicy(content(), pair, ISSUER, "untrusted-key", ISSUED_AT);
            verifier.verify(valid);
            verifier.verify(unknownKey);
            assertThat(meters.get("model_promotion_policy_authority_verification_total")
                    .tag("status", "VERIFIED").tag("reason", "NONE").counter().count()).isEqualTo(1);
            assertThat(meters.get("model_promotion_policy_authority_verification_total")
                    .tag("status", "REJECTED").tag("reason", "POLICY_KEY_UNKNOWN").counter().count())
                    .isEqualTo(1);
            assertThat(meters.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
                    .map(io.micrometer.core.instrument.Tag::getValue).toList())
                    .doesNotContain(ISSUER, KEY_ID, "untrusted-key");
        } finally {
            meters.close();
        }
    }

    private void assertRejected(ModelPromotionPolicyAuthorityVerification result) {
        assertThat(result.status()).isEqualTo(ModelPromotionPolicyAuthorityVerification.Status.REJECTED);
    }

    private Ed25519ModelPromotionPolicyAuthorityVerifier verifier(KeyPair pair) throws Exception {
        return verifier(pair, List.of(ModelGovernanceEnvironment.PRODUCTION),
                ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE, "2025-01-01T00:00:00Z",
                "2027-01-01T00:00:00Z");
    }

    private Ed25519ModelPromotionPolicyAuthorityVerifier verifier(KeyPair pair,
            List<ModelGovernanceEnvironment> environments, ModelPromotionTrustedIssuerRegistry.KeyStatus status,
            String from, String until) throws Exception {
        ModelPromotionTrustedIssuerRegistry.ConfiguredKey base = configuredKey(pair);
        ModelPromotionTrustedIssuerRegistry.ConfiguredKey entry = new ModelPromotionTrustedIssuerRegistry.ConfiguredKey(
                ISSUER, KEY_ID, base.publicKeySpkiBase64(), base.publicKeySha256(), base.institutionalScopes(),
                environments, base.intendedUses(), from, until, status);
        return new Ed25519ModelPromotionPolicyAuthorityVerifier(mapper, registry(List.of(entry)), CLOCK);
    }

    private ModelPromotionTrustedIssuerRegistry registry(List<ModelPromotionTrustedIssuerRegistry.ConfiguredKey> keys) {
        return registry(keys, 1, 1);
    }

    private ModelPromotionTrustedIssuerRegistry registry(List<ModelPromotionTrustedIssuerRegistry.ConfiguredKey> keys,
            long generation, long minimumGeneration) {
        return ModelPromotionTrustedIssuerRegistry.fromConfiguration(
                new ModelPromotionTrustedIssuerRegistry.Configuration(ModelPromotionTrustedIssuerRegistry.VERSION,
                        generation, keys), minimumGeneration);
    }

    private ModelPromotionTrustedIssuerRegistry.ConfiguredKey key(KeyPair pair, String issuer, String keyId,
            ModelPromotionTrustedIssuerRegistry.KeyStatus status, String from, String until) throws Exception {
        ModelPromotionTrustedIssuerRegistry.ConfiguredKey source = configuredKey(pair);
        return new ModelPromotionTrustedIssuerRegistry.ConfiguredKey(issuer, keyId, source.publicKeySpkiBase64(),
                source.publicKeySha256(), source.institutionalScopes(), source.environments(), source.intendedUses(),
                from, until, status);
    }

    private ModelPromotionPolicy.Content withPeriod(ModelPromotionPolicy.Content source, String from, String until) {
        return new ModelPromotionPolicy.Content(source.policyVersion(), source.policyId(), source.revision(),
                source.governanceScope(), source.intendedUse(), source.provenance(), from, until, source.criteria());
    }

    private ModelPromotionTrustedIssuerRegistry.ConfiguredKey configuredKey(KeyPair pair) throws Exception {
        byte[] publicKey = pair.getPublic().getEncoded();
        return new ModelPromotionTrustedIssuerRegistry.ConfiguredKey(ISSUER, KEY_ID,
                Base64.getEncoder().encodeToString(publicKey),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey)),
                List.of(ModelGovernanceScope.CURRENT_INSTITUTION),
                List.of(ModelGovernanceEnvironment.PRODUCTION), List.of(ModelPromotionIntendedUse.FRAUD_RISK_SCORING),
                "2025-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE);
    }

    private ModelPromotionTrustedIssuerRegistry.ConfiguredKey withPublicKey(
            ModelPromotionTrustedIssuerRegistry.ConfiguredKey source, String publicKey) {
        return new ModelPromotionTrustedIssuerRegistry.ConfiguredKey(source.issuerIdentity(), source.keyId(), publicKey,
                source.publicKeySha256(), source.institutionalScopes(), source.environments(), source.intendedUses(),
                source.validFrom(), source.validUntil(), source.status());
    }

    private ModelPromotionPolicy.Content content() {
        ModelPromotionPolicy.Content base = ModelPromotionAssessmentTestFixtures.policy().content();
        return new ModelPromotionPolicy.Content(base.policyVersion(), "approved-policy", base.revision(),
                new ModelGovernanceScope(ModelGovernanceScope.CURRENT_INSTITUTION,
                        ModelGovernanceEnvironment.PRODUCTION), base.intendedUse(),
                new ModelPromotionPolicy.PolicyProvenance(ModelPromotionPolicy.PolicyAuthority.INSTITUTIONAL_APPROVED,
                        "policy-author", "risk-committee", "2025-12-31T00:00:00Z", "approval-1"),
                base.effectiveFromInclusive(), base.effectiveToExclusive(), base.criteria());
    }

    private ModelPromotionPolicy.Content copy(ModelPromotionPolicy.Content source, long revision,
            ModelGovernanceScope scope, ModelPromotionPolicy.PolicyProvenance provenance,
            List<ModelPromotionPolicy.CriterionRule> criteria) {
        return new ModelPromotionPolicy.Content(source.policyVersion(), source.policyId(), revision, scope,
                source.intendedUse(), provenance, source.effectiveFromInclusive(), source.effectiveToExclusive(),
                criteria);
    }

    private List<ModelPromotionPolicy.CriterionRule> changedThreshold(List<ModelPromotionPolicy.CriterionRule> rules) {
        return rules.stream().map(rule -> {
            if (rule.criterion() != ModelPromotionCriterion.STATISTICALLY_MEANINGFUL_SAMPLE) {
                return rule;
            }
            Map<String, BigDecimal> thresholds = new java.util.HashMap<>(rule.thresholds());
            thresholds.put("minimumEvaluatedRecords", thresholds.get("minimumEvaluatedRecords").add(BigDecimal.ONE));
            return new ModelPromotionPolicy.CriterionRule(rule.criterion(), rule.applicability(), thresholds,
                    rule.maximumEvidenceAgeSeconds(), rule.requiredVerificationLevel(), rule.evidenceSource(),
                    rule.metricSemantics(), rule.populationDefinition(), rule.denominatorDefinition(),
                    rule.applicabilityJustification());
        }).toList();
    }

    private ModelPromotionPolicy signedPolicy(ModelPromotionPolicy.Content content, KeyPair pair,
            String issuer, String keyId, String issuedAt) throws Exception {
        String digest = new ModelPromotionPolicyValidator(mapper).canonicalSha256(content);
        byte[] input = ModelPromotionPolicySignatureContract.signingBytes(mapper, content, issuer, keyId, issuedAt);
        return new ModelPromotionPolicy(content, digest, new ModelPromotionPolicy.PolicyAuthorizationEvidence(
                issuer, keyId, ModelPromotionPolicySignatureContract.encodeSignature(sign(pair, input)), digest,
                issuedAt));
    }

    private ModelPromotionPolicy withSignature(ModelPromotionPolicy policy, byte[] signature) {
        return withEncodedSignature(policy, ModelPromotionPolicySignatureContract.encodeSignature(signature));
    }

    private ModelPromotionPolicy withEncodedSignature(ModelPromotionPolicy policy, String signature) {
        ModelPromotionPolicy.PolicyAuthorizationEvidence source = policy.authorizationEvidence();
        return new ModelPromotionPolicy(policy.content(), policy.canonicalSha256(),
                new ModelPromotionPolicy.PolicyAuthorizationEvidence(source.authorityIdentity(), source.keyId(),
                        signature, source.policyCanonicalSha256(), source.issuedAt()));
    }

    private byte[] sign(KeyPair pair, byte[] input) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(input);
        return signer.sign();
    }

    private KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }
}
