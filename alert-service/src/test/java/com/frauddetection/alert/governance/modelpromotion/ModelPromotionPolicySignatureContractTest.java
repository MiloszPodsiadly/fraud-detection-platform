package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelPromotionPolicySignatureContractTest {

    // RFC 8032 test vector 1. These well-known test-only key bytes are never packaged as runtime resources.
    private static final String TEST_PRIVATE_KEY = "302e020100300506032b657004220420"
            + "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";
    private static final String TEST_PUBLIC_KEY = "302a300506032b6570032100"
            + "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    private static final String ISSUER = "institutional-policy-issuer";
    private static final String KEY_ID = "issuer-key-1";
    private static final String ISSUED_AT = "2025-12-31T12:00:00Z";
    private static final String EXPECTED_POLICY_DIGEST =
            "150c4c6f32ebfde145b4449618e3f07ed577dca2aed523c8ae44f5b03921ccda";
    private static final String EXPECTED_SIGNATURE = "c841c49d514b296430645812fc46cd7665be35a3d46fef0a6eb718d5551e636"
            + "51a34762b2d3e0f3b1ed018a9c827ec3bfffa717a96b1343c3994dc5a791b4c09";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModelPromotionPolicyValidator validator = new ModelPromotionPolicyValidator(objectMapper);

    @Test
    void fixedTestKeySignsAndVerifiesTheExactPolicyEnvelope() throws Exception {
        ModelPromotionPolicy.Content content = institutionalContent();
        byte[] input = signingBytes(content, ISSUER, KEY_ID, ISSUED_AT);
        byte[] signature = sign(input);
        ModelPromotionPolicy policy = signedPolicy(content, ISSUER, KEY_ID, ISSUED_AT,
                ModelPromotionPolicySignatureContract.encodeSignature(signature));

        assertThat(signature).hasSize(64);
        assertThat(HexFormat.of().formatHex(signature)).isEqualTo(EXPECTED_SIGNATURE);
        assertThat(ModelPromotionPolicySignatureContract.verificationBytes(objectMapper, policy)).isEqualTo(input);
        assertThat(ModelPromotionPolicySignatureContract.decodeSignature(policy.authorizationEvidence().signature()))
                .isEqualTo(signature);
        assertThat(verifies(input, signature)).isTrue();
        validator.validate(policy, content.governanceScope(), content.intendedUse(),
                ModelPromotionAssessmentTestFixtures.ASSESSMENT_TIME);
    }

    @Test
    void testVectorIndependentlySpecifiesFieldOrderUtf8AndOneTrailingNewline() {
        ModelPromotionPolicy.Content content = institutionalContent();
        String digest = validator.canonicalSha256(content);
        assertThat(digest).isEqualTo(EXPECTED_POLICY_DIGEST);
        String expected = "{\"authorityIdentity\":\"" + ISSUER
                + "\",\"contractVersion\":\"model-promotion-policy-signature-v1\""
                + ",\"issuedAt\":\"" + ISSUED_AT
                + "\",\"keyId\":\"" + KEY_ID
                + "\",\"policyCanonicalSha256\":\"" + digest
                + "\",\"purpose\":\"MODEL_PROMOTION_POLICY_APPROVAL_V1\"}\n";

        assertThat(signingBytes(content, ISSUER, KEY_ID, ISSUED_AT))
                .isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
        assertThat(signingBytes(content, ISSUER, KEY_ID, ISSUED_AT))
                .isEqualTo(ModelPromotionPolicySignatureContract.signingBytes(
                        new ObjectMapper(), content, ISSUER, KEY_ID, ISSUED_AT));
    }

    @Test
    void revisionScopeThresholdAndApprovalProvenanceCannotReuseTheSignature() throws Exception {
        ModelPromotionPolicy.Content original = institutionalContent();
        byte[] originalSignature = sign(signingBytes(original, ISSUER, KEY_ID, ISSUED_AT));
        ModelPromotionPolicy.Content changedRevision = copy(original, 2, original.governanceScope(),
                original.provenance(), original.criteria());
        ModelPromotionPolicy.Content changedScope = copy(original, original.revision(),
                new ModelGovernanceScope(ModelGovernanceScope.CURRENT_INSTITUTION, ModelGovernanceEnvironment.TEST),
                original.provenance(), original.criteria());
        ModelPromotionPolicy.PolicyProvenance source = original.provenance();
        ModelPromotionPolicy.Content changedApproval = copy(original, original.revision(), original.governanceScope(),
                new ModelPromotionPolicy.PolicyProvenance(source.authority(), source.authorIdentity(),
                        source.approvingAuthority(), source.approvedAt(), "different-approval"), original.criteria());
        ModelPromotionPolicy.Content changedThreshold = copy(original, original.revision(), original.governanceScope(),
                original.provenance(), withChangedThreshold(original.criteria()));

        for (ModelPromotionPolicy.Content changed : List.of(
                changedRevision, changedScope, changedApproval, changedThreshold
        )) {
            ModelPromotionPolicy policy = signedPolicy(changed, ISSUER, KEY_ID, ISSUED_AT,
                    ModelPromotionPolicySignatureContract.encodeSignature(originalSignature));
            assertThat(verifies(ModelPromotionPolicySignatureContract.verificationBytes(objectMapper, policy),
                    originalSignature)).isFalse();
        }
    }

    @Test
    void issuerKeyAndIssuanceTimeAreSignedFields() throws Exception {
        ModelPromotionPolicy.Content content = institutionalContent();
        byte[] originalSignature = sign(signingBytes(content, ISSUER, KEY_ID, ISSUED_AT));
        for (String[] variant : List.of(
                new String[] {"different-issuer", KEY_ID, ISSUED_AT},
                new String[] {ISSUER, "different-key", ISSUED_AT},
                new String[] {ISSUER, KEY_ID, "2025-12-31T13:00:00Z"}
        )) {
            assertThat(verifies(signingBytes(content, variant[0], variant[1], variant[2]), originalSignature))
                    .isFalse();
        }
    }

    @Test
    void authorApproverAndIssuerIdentitiesMustRemainDistinct() {
        ModelPromotionPolicy.Content content = institutionalContent();
        ModelPromotionPolicy.PolicyProvenance source = content.provenance();
        ModelPromotionPolicy.Content sameAuthorAndApprover = copy(content, content.revision(),
                content.governanceScope(), new ModelPromotionPolicy.PolicyProvenance(source.authority(),
                        source.authorIdentity(), source.authorIdentity(), source.approvedAt(),
                        source.approvalReference()), content.criteria());

        for (String issuer : List.of(source.authorIdentity(), source.approvingAuthority())) {
            assertThatThrownBy(() -> signingBytes(content, issuer, KEY_ID, ISSUED_AT))
                    .isInstanceOf(IllegalArgumentException.class);
            ModelPromotionPolicy policy = signedPolicy(content, issuer, KEY_ID, ISSUED_AT, "test-only-placeholder");
            assertThatThrownBy(() -> validator.validate(policy, content.governanceScope(), content.intendedUse(),
                    ModelPromotionAssessmentTestFixtures.ASSESSMENT_TIME))
                    .isInstanceOf(ModelPromotionPolicyValidator.InvalidPolicyException.class);
        }
        assertThatThrownBy(() -> signingBytes(sameAuthorAndApprover, ISSUER, KEY_ID, ISSUED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        ModelPromotionPolicy policy = signedPolicy(sameAuthorAndApprover, ISSUER, KEY_ID, ISSUED_AT,
                "test-only-placeholder");
        assertThatThrownBy(() -> validator.validate(policy, content.governanceScope(), content.intendedUse(),
                ModelPromotionAssessmentTestFixtures.ASSESSMENT_TIME))
                .isInstanceOf(ModelPromotionPolicyValidator.InvalidPolicyException.class);
    }

    @Test
    void auditAnchorPurposeAndModifiedSignatureCannotVerify() throws Exception {
        ModelPromotionPolicy.Content content = institutionalContent();
        byte[] expectedInput = signingBytes(content, ISSUER, KEY_ID, ISSUED_AT);
        byte[] otherPurpose = GovernanceCanonicalJson.serialize(objectMapper, objectMapper.valueToTree(Map.of(
                "authorityIdentity", ISSUER,
                "contractVersion", ModelPromotionPolicySignatureContract.CONTRACT_VERSION,
                "issuedAt", ISSUED_AT,
                "keyId", KEY_ID,
                "policyCanonicalSha256", validator.canonicalSha256(content),
                "purpose", "AUDIT_ANCHOR"
        )));
        assertThat(verifies(expectedInput, sign(otherPurpose))).isFalse();

        byte[] corrupted = sign(expectedInput);
        corrupted[0] ^= 1;
        assertThat(verifies(expectedInput, corrupted)).isFalse();
    }

    @Test
    void malformedOrNoncanonicalTimestampAndSignatureAreRejected() {
        ModelPromotionPolicy.Content content = institutionalContent();
        for (String timestamp : List.of(
                "2025-12-31T12:00:00.0Z", "2025-12-31T12:00:00+00:00",
                "2025-12-30T23:59:59Z", "2026-01-01T00:00:01Z"
        )) {
            assertThatThrownBy(() -> signingBytes(content, ISSUER, KEY_ID, timestamp))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ModelPromotionPolicySignatureContract.decodeSignature("!!!"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelPromotionPolicySignatureContract.decodeSignature("AA=="))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelPromotionPolicySignatureContract.decodeSignature(
                ModelPromotionPolicySignatureContract.encodeSignature(new byte[64]) + "="))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelPromotionPolicySignatureContract.encodeSignature(new byte[63]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatorRejectsAnInstitutionalSignatureIssuedBeforeApprovalOrWithNoncanonicalTime() {
        ModelPromotionPolicy.Content content = institutionalContent();
        for (String issuedAt : List.of("2025-12-30T23:59:59Z", "2025-12-31T12:00:00.0Z")) {
            ModelPromotionPolicy policy = signedPolicy(content, ISSUER, KEY_ID, issuedAt, "test-only-placeholder");
            assertThatThrownBy(() -> validator.validate(policy, content.governanceScope(), content.intendedUse(),
                    ModelPromotionAssessmentTestFixtures.ASSESSMENT_TIME))
                    .isInstanceOf(ModelPromotionPolicyValidator.InvalidPolicyException.class)
                    .satisfies(error -> assertThat(((ModelPromotionPolicyValidator.InvalidPolicyException) error).reason())
                            .isEqualTo(ModelPromotionPolicyResolution.Reason.POLICY_CONTRACT_INVALID));
        }
    }

    @Test
    void serializationRoundTripPreservesSignatureInputBytes() throws Exception {
        ModelPromotionPolicy.Content content = institutionalContent();
        byte[] signature = sign(signingBytes(content, ISSUER, KEY_ID, ISSUED_AT));
        ModelPromotionPolicy original = signedPolicy(content, ISSUER, KEY_ID, ISSUED_AT,
                ModelPromotionPolicySignatureContract.encodeSignature(signature));
        ModelPromotionPolicy decoded = objectMapper.readValue(
                objectMapper.writeValueAsString(original), ModelPromotionPolicy.class
        );

        assertThat(ModelPromotionPolicySignatureContract.verificationBytes(new ObjectMapper(), decoded))
                .isEqualTo(ModelPromotionPolicySignatureContract.verificationBytes(objectMapper, original));
        assertThat(verifies(ModelPromotionPolicySignatureContract.verificationBytes(new ObjectMapper(), decoded),
                ModelPromotionPolicySignatureContract.decodeSignature(decoded.authorizationEvidence().signature())))
                .isTrue();
    }

    private ModelPromotionPolicy.Content institutionalContent() {
        ModelPromotionPolicy.Content base = ModelPromotionAssessmentTestFixtures.policy().content();
        return new ModelPromotionPolicy.Content(
                base.policyVersion(), "institutional-policy", base.revision(), base.governanceScope(),
                base.intendedUse(), new ModelPromotionPolicy.PolicyProvenance(
                        ModelPromotionPolicy.PolicyAuthority.INSTITUTIONAL_APPROVED,
                        "policy-author", "risk-committee", "2025-12-31T00:00:00Z", "approval-1"
                ), base.effectiveFromInclusive(), base.effectiveToExclusive(), base.criteria()
        );
    }

    private ModelPromotionPolicy.Content copy(
            ModelPromotionPolicy.Content source,
            long revision,
            ModelGovernanceScope scope,
            ModelPromotionPolicy.PolicyProvenance provenance,
            List<ModelPromotionPolicy.CriterionRule> criteria
    ) {
        return new ModelPromotionPolicy.Content(
                source.policyVersion(), source.policyId(), revision, scope, source.intendedUse(), provenance,
                source.effectiveFromInclusive(), source.effectiveToExclusive(), criteria
        );
    }

    private List<ModelPromotionPolicy.CriterionRule> withChangedThreshold(
            List<ModelPromotionPolicy.CriterionRule> rules
    ) {
        return rules.stream().map(rule -> {
            if (rule.criterion() != ModelPromotionCriterion.STATISTICALLY_MEANINGFUL_SAMPLE) {
                return rule;
            }
            Map<String, BigDecimal> thresholds = new HashMap<>(rule.thresholds());
            thresholds.put("minimumEvaluatedRecords", thresholds.get("minimumEvaluatedRecords").add(BigDecimal.ONE));
            return new ModelPromotionPolicy.CriterionRule(
                    rule.criterion(), rule.applicability(), thresholds, rule.maximumEvidenceAgeSeconds(),
                    rule.requiredVerificationLevel(), rule.evidenceSource(), rule.metricSemantics(),
                    rule.populationDefinition(), rule.denominatorDefinition(), rule.applicabilityJustification()
            );
        }).toList();
    }

    private ModelPromotionPolicy signedPolicy(
            ModelPromotionPolicy.Content content,
            String issuer,
            String keyId,
            String issuedAt,
            String signature
    ) {
        String digest = validator.canonicalSha256(content);
        return new ModelPromotionPolicy(content, digest, new ModelPromotionPolicy.PolicyAuthorizationEvidence(
                issuer, keyId, signature, digest, issuedAt
        ));
    }

    private byte[] signingBytes(
            ModelPromotionPolicy.Content content,
            String issuer,
            String keyId,
            String issuedAt
    ) {
        return ModelPromotionPolicySignatureContract.signingBytes(objectMapper, content, issuer, keyId, issuedAt);
    }

    private byte[] sign(byte[] input) throws Exception {
        PrivateKey privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(
                HexFormat.of().parseHex(TEST_PRIVATE_KEY)
        ));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(input);
        return signer.sign();
    }

    private boolean verifies(byte[] input, byte[] signatureBytes) throws Exception {
        PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
                HexFormat.of().parseHex(TEST_PUBLIC_KEY)
        ));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(input);
        return verifier.verify(signatureBytes);
    }
}
