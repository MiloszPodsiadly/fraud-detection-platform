package com.frauddetection.enricher.merchant;

import com.frauddetection.common.events.source.SourceReceiptIdentity;
import com.frauddetection.common.events.source.SourceScope;
import com.frauddetection.common.events.source.SourceSignedInput;
import com.frauddetection.common.events.source.SourceSignatureVerifier;
import com.frauddetection.common.events.source.SourceVerificationProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static com.frauddetection.enricher.merchant.MerchantAssertionTrustPolicy.*;
import static com.frauddetection.enricher.merchant.MerchantAssertionVerifier.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MerchantAssertionVerifierTest {
    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String AUDIENCE = MerchantAssertionTrustPolicy.AUDIENCE;
    private static final String SOURCE_ISSUER = "source-issuer";
    private static final String INSTITUTION = "institution-1";
    private static final String SOURCE = "transaction-feed";
    private static final String EPOCH = "epoch-1";
    private static final String TRANSACTION_PURPOSE = "TRANSACTION_SOURCE_ATTESTATION";
    private static final String SOURCE_AUDIENCE = "transaction-ingest";
    private static final String SOURCE_EVENT = "a".repeat(64);
    private static final String TRANSACTION_COMMITMENT = "b".repeat(64);
    private static final String OCCURRENCE_COMMITMENT = "c".repeat(64);
    private static final String RAW_COMMITMENT = "d".repeat(64);
    private static final String ENRICHED_COMMITMENT = "e".repeat(64);
    private static final String MERCHANT_PSEUDONYM = "f".repeat(64);
    private static final String MAPPING_COMMITMENT = "1".repeat(64);
    private static final String POPULATION_COMMITMENT = "2".repeat(64);
    private static final String INCLUSION_COMMITMENT = "3".repeat(64);
    private static final String EXCLUSION_COMMITMENT = "4".repeat(64);
    private static final String OBSERVATION_GRANT_ID = "merchant-observation-grant-v1";
    private static final String COVERAGE_GRANT_ID = "merchant-coverage-grant-v1";

    @Test
    void validObservationAndIndependentlyAuthorizedCoverageVerifyAndChain() {
        Fixture fixture = new Fixture();
        byte[] observation = MerchantAssertionCodec.encodeObservation(fixture.observation());
        Result observationResult = fixture.verifier.verifyObservation(
                fixture.sign(observation, fixture.observationKey, fixture.observationPair, SOURCE_EVENT, NOW,
                        OBSERVATION_PURPOSE, AUDIENCE), observation, fixture.expected);
        assertThat(observationResult).isInstanceOf(VerifiedObservation.class);
        VerifiedObservation verifiedObservation = (VerifiedObservation) observationResult;
        assertThat(fixture.verifier.currentDenial(verifiedObservation)).isNull();
        assertThat(verifiedObservation.assertion()).isEqualTo(fixture.observation());
        assertThat(verifiedObservation.originalAssertionBytes()).containsExactly(observation);

        byte[] coverage = MerchantAssertionCodec.encodeCoverage(fixture.coverage(1, 1, 1, null, NOW));
        Result coverageResult = fixture.verifier.verifyCoverage(
                fixture.sign(coverage, fixture.coverageKey, fixture.coveragePair, "coverage-1", NOW,
                        COVERAGE_PURPOSE, AUDIENCE), coverage, fixture.origin, null);
        assertThat(coverageResult).isInstanceOf(VerifiedCoverage.class);
        VerifiedCoverage first = (VerifiedCoverage) coverageResult;
        assertThat(fixture.verifier.currentDenial(first)).isNull();

        Instant nextWatermark = NOW.plusSeconds(60);
        MerchantCoverageAssertion nextClaim = fixture.coverage(2, 2, 8, first.assertionCommitment(), nextWatermark);
        byte[] nextBytes = MerchantAssertionCodec.encodeCoverage(nextClaim);
        Result nextResult = fixture.verifier.verifyCoverage(
                fixture.sign(nextBytes, fixture.coverageKey, fixture.coveragePair, "coverage-2", nextWatermark,
                        COVERAGE_PURPOSE, AUDIENCE), nextBytes, fixture.origin, first);
        assertThat(nextResult).isInstanceOf(VerifiedCoverage.class);
    }

    @Test
    void originalBytesAreVerifiedRatherThanParsedAndReserialized() {
        Fixture fixture = new Fixture();
        byte[] original = MerchantAssertionCodec.encodeObservation(fixture.observation());
        byte[] proof = fixture.sign(original, fixture.observationKey, fixture.observationPair, SOURCE_EVENT, NOW,
                OBSERVATION_PURPOSE, AUDIENCE);

        byte[] contentChanged = new String(original, StandardCharsets.UTF_8)
                .replace("\"sequence\":1", "\"sequence\":2").getBytes(StandardCharsets.UTF_8);
        assertThat(fixture.verifier.verifyObservation(proof, contentChanged, fixture.expected))
                .isEqualTo(Denied.SIGNATURE_OR_POLICY_DENIED);

        byte[] nonCanonical = (" " + new String(original, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        assertThat(fixture.verifier.verifyObservation(proof, nonCanonical, fixture.expected))
                .isEqualTo(Denied.MALFORMED_ASSERTION);
    }

    @Test
    void distinctRolePurposeAudienceAndKeyMaterialAreRequired() {
        Fixture fixture = new Fixture();
        byte[] observation = MerchantAssertionCodec.encodeObservation(fixture.observation());
        byte[] coverageKeyProof = fixture.sign(observation, fixture.coverageKey, fixture.coveragePair,
                SOURCE_EVENT, NOW, COVERAGE_PURPOSE, AUDIENCE);
        assertThat(fixture.verifier.verifyObservation(coverageKeyProof, observation, fixture.expected))
                .isEqualTo(Denied.SIGNATURE_OR_POLICY_DENIED);

        byte[] wrongAudienceProof = fixture.sign(observation, fixture.observationKey, fixture.observationPair,
                SOURCE_EVENT, NOW, OBSERVATION_PURPOSE, "other-private-reader");
        assertThat(fixture.verifier.verifyObservation(wrongAudienceProof, observation, fixture.expected))
                .isEqualTo(Denied.SIGNATURE_OR_POLICY_DENIED);

        byte[] coverage = MerchantAssertionCodec.encodeCoverage(fixture.coverage(1, 1, 1, null, NOW));
        byte[] observationKeyCoverageProof = fixture.sign(coverage, fixture.observationKey, fixture.observationPair,
                "coverage-1", NOW, COVERAGE_PURPOSE, AUDIENCE);
        assertThat(fixture.verifier.verifyCoverage(observationKeyCoverageProof, coverage, fixture.origin, null))
                .isEqualTo(Denied.SIGNATURE_OR_POLICY_DENIED);

        SourceVerificationProperties.TrustedKey reused = fixture.key("coverage-issuer", "coverage-v1",
                COVERAGE_PURPOSE, AUDIENCE, fixture.observationPair, NOW.minusSeconds(86_400), NOW.plusSeconds(86_400), false);
        Grant sharedKeyCoverage = new Grant(Role.COVERAGE, COVERAGE_GRANT_ID, 1, reused,
                fixture.sourceIdentity, fixture.population, null);
        assertThatThrownBy(() -> fixture.verifier(List.of(fixture.observationGrant, sharedKeyCoverage)))
                .hasMessage("MERCHANT_ASSERTION_POLICY_INVALID");
    }

    @Test
    void revokedExpiredAndReplacedMerchantKeysFailClosed() {
        Fixture fixture = new Fixture();
        byte[] observation = MerchantAssertionCodec.encodeObservation(fixture.observation());
        byte[] validProof = fixture.sign(observation, fixture.observationKey, fixture.observationPair, SOURCE_EVENT, NOW,
                OBSERVATION_PURPOSE, AUDIENCE);

        Grant revoked = fixture.observationGrant(fixture.observationPair, true, NOW.minusSeconds(86_400), NOW.plusSeconds(86_400));
        assertThat(fixture.verifier(List.of(revoked, fixture.coverageGrant))
                .verifyObservation(validProof, observation, fixture.expected)).isEqualTo(Denied.SIGNATURE_OR_POLICY_DENIED);

        Grant expired = fixture.observationGrant(fixture.observationPair, false, NOW.minusSeconds(86_400), NOW);
        assertThat(fixture.verifier(List.of(expired, fixture.coverageGrant))
                .verifyObservation(validProof, observation, fixture.expected)).isEqualTo(Denied.SIGNATURE_OR_POLICY_DENIED);

        KeyPair replacementPair = pair();
        Grant replacement = fixture.observationGrant(replacementPair, false, NOW.minusSeconds(86_400), NOW.plusSeconds(86_400));
        assertThat(fixture.verifier(List.of(replacement, fixture.coverageGrant))
                .verifyObservation(validProof, observation, fixture.expected)).isEqualTo(Denied.SIGNATURE_OR_POLICY_DENIED);
    }

    @Test
    void sourceScopeMerchantMappingAndPopulationMustMatchIndependentGrants() {
        Fixture fixture = new Fixture();
        byte[] wrongSourceIssuer = MerchantAssertionCodec.encodeObservation(fixture.observation(
                "other-source-issuer", INSTITUTION, SOURCE, EPOCH, MERCHANT_PSEUDONYM, 1,
                fixture.population.id(), 1));
        assertThat(fixture.verifyObservation(wrongSourceIssuer)).isEqualTo(Denied.SOURCE_BINDING_MISMATCH);

        byte[] wrongInstitution = MerchantAssertionCodec.encodeObservation(fixture.observation(
                SOURCE_ISSUER, "other-institution", SOURCE, EPOCH, MERCHANT_PSEUDONYM, 1,
                fixture.population.id(), 1));
        assertThat(fixture.verifyObservation(wrongInstitution)).isEqualTo(Denied.SOURCE_BINDING_MISMATCH);

        byte[] wrongSource = MerchantAssertionCodec.encodeObservation(fixture.observation(
                SOURCE_ISSUER, INSTITUTION, "other-source", EPOCH, MERCHANT_PSEUDONYM, 1,
                fixture.population.id(), 1));
        assertThat(fixture.verifyObservation(wrongSource)).isEqualTo(Denied.SOURCE_BINDING_MISMATCH);

        byte[] wrongEpoch = MerchantAssertionCodec.encodeObservation(fixture.observation(
                SOURCE_ISSUER, INSTITUTION, SOURCE, "other-epoch", MERCHANT_PSEUDONYM, 1,
                fixture.population.id(), 1));
        assertThat(fixture.verifyObservation(wrongEpoch)).isEqualTo(Denied.SOURCE_BINDING_MISMATCH);

        byte[] swappedMerchant = MerchantAssertionCodec.encodeObservation(fixture.observation(
                SOURCE_ISSUER, INSTITUTION, SOURCE, EPOCH, "9".repeat(64), 1,
                fixture.population.id(), 1));
        assertThat(fixture.verifyObservation(swappedMerchant)).isEqualTo(Denied.MAPPING_NOT_AUTHORIZED);

        byte[] wrongMappingVersion = MerchantAssertionCodec.encodeObservation(fixture.observation(
                SOURCE_ISSUER, INSTITUTION, SOURCE, EPOCH, MERCHANT_PSEUDONYM, 2,
                fixture.population.id(), 1));
        assertThat(fixture.verifyObservation(wrongMappingVersion)).isEqualTo(Denied.MAPPING_NOT_AUTHORIZED);

        assertThat(fixture.verifyObservation(MerchantAssertionCodec.encodeObservation(
                fixture.observationWithSourceCommitments("9".repeat(64), OCCURRENCE_COMMITMENT))))
                .isEqualTo(Denied.SOURCE_BINDING_MISMATCH);

        byte[] unknownPopulation = MerchantAssertionCodec.encodeObservation(fixture.observation(
                SOURCE_ISSUER, INSTITUTION, SOURCE, EPOCH, MERCHANT_PSEUDONYM, 1,
                "other-population", 1));
        assertThat(fixture.verifyObservation(unknownPopulation)).isEqualTo(Denied.POPULATION_NOT_AUTHORIZED);

        assertThat(fixture.verifyObservation(MerchantAssertionCodec.encodeObservation(
                fixture.observationWithGrant("unknown-observation-grant", 1)))).isEqualTo(Denied.AUTHORITY_NOT_AUTHORIZED);
        assertThat(fixture.verifyObservation(MerchantAssertionCodec.encodeObservation(
                fixture.observationWithGrant(OBSERVATION_GRANT_ID, 2)))).isEqualTo(Denied.AUTHORITY_NOT_AUTHORIZED);
        assertThat(fixture.verifyObservation(MerchantAssertionCodec.encodeObservation(
                fixture.observationWithPolicy("policy-v2")))).isEqualTo(Denied.AUTHORITY_NOT_AUTHORIZED);
    }

    @Test
    void duplicateUnknownMalformedAndOversizedAssertionsAreRejected() {
        Fixture fixture = new Fixture();
        byte[] valid = MerchantAssertionCodec.encodeObservation(fixture.observation());
        byte[] proof = fixture.sign(valid, fixture.observationKey, fixture.observationPair, SOURCE_EVENT, NOW,
                OBSERVATION_PURPOSE, AUDIENCE);
        String text = new String(valid, StandardCharsets.UTF_8);

        byte[] duplicate = (text.substring(0, text.length() - 1) + ",\"assertionVersion\":1}").getBytes(StandardCharsets.UTF_8);
        assertThat(fixture.verifier.verifyObservation(proof, duplicate, fixture.expected)).isEqualTo(Denied.MALFORMED_ASSERTION);

        byte[] unknown = (text.substring(0, text.length() - 1) + ",\"unexpected\":true}").getBytes(StandardCharsets.UTF_8);
        assertThat(fixture.verifier.verifyObservation(proof, unknown, fixture.expected)).isEqualTo(Denied.MALFORMED_ASSERTION);

        assertThat(fixture.verifier.verifyObservation(proof, new byte[] {(byte) 0xc3, (byte) 0x28}, fixture.expected))
                .isEqualTo(Denied.MALFORMED_ASSERTION);
        assertThat(fixture.verifier.verifyObservation(proof, new byte[MerchantAssertionCodec.MAX_ASSERTION_BYTES + 1], fixture.expected))
                .isEqualTo(Denied.PAYLOAD_REJECTED);
        assertThat(fixture.verifier.verifyObservation(new byte[4_097], valid, fixture.expected)).isEqualTo(Denied.PROOF_REJECTED);

        byte[] validCoverage = MerchantAssertionCodec.encodeCoverage(fixture.coverage(1, 1, 2, null, NOW));
        byte[] coverageProof = fixture.sign(validCoverage, fixture.coverageKey, fixture.coveragePair,
                "coverage-1", NOW, COVERAGE_PURPOSE, AUDIENCE);
        String coverageText = new String(validCoverage, StandardCharsets.UTF_8);
        byte[] unknownCoverage = (coverageText.substring(0, coverageText.length() - 1) + ",\"unexpected\":true}")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(fixture.verifier.verifyCoverage(coverageProof, unknownCoverage, fixture.origin, null))
                .isEqualTo(Denied.MALFORMED_ASSERTION);
    }

    @Test
    void coverageMustStartAtOneAndContinueTheSignedGenerationChain() {
        Fixture fixture = new Fixture();
        MerchantCoverageAssertion firstClaim = fixture.coverage(1, 1, 2, null, NOW);
        byte[] firstBytes = MerchantAssertionCodec.encodeCoverage(firstClaim);
        VerifiedCoverage first = (VerifiedCoverage) fixture.verifier.verifyCoverage(
                fixture.sign(firstBytes, fixture.coverageKey, fixture.coveragePair, "coverage-1", NOW,
                        COVERAGE_PURPOSE, AUDIENCE), firstBytes, fixture.origin, null);

        MerchantCoverageAssertion gap = fixture.coverage(2, 4, 5, first.assertionCommitment(), NOW.plusSeconds(30));
        byte[] gapBytes = MerchantAssertionCodec.encodeCoverage(gap);
        assertThat(fixture.verifier.verifyCoverage(
                fixture.sign(gapBytes, fixture.coverageKey, fixture.coveragePair, "coverage-2", NOW.plusSeconds(30),
                        COVERAGE_PURPOSE, AUDIENCE), gapBytes, fixture.origin, first))
                .isEqualTo(Denied.COVERAGE_CHAIN_MISMATCH);

        MerchantCoverageAssertion wrongPrevious = fixture.coverage(2, 3, 4, "8".repeat(64), NOW.plusSeconds(30));
        byte[] wrongPreviousBytes = MerchantAssertionCodec.encodeCoverage(wrongPrevious);
        assertThat(fixture.verifier.verifyCoverage(
                fixture.sign(wrongPreviousBytes, fixture.coverageKey, fixture.coveragePair, "coverage-2", NOW.plusSeconds(30),
                        COVERAGE_PURPOSE, AUDIENCE), wrongPreviousBytes, fixture.origin, first))
                .isEqualTo(Denied.COVERAGE_CHAIN_MISMATCH);

        MerchantCoverageAssertion wrongOrigin = fixture.coverage(1, 2, 2, null, NOW);
        byte[] wrongOriginBytes = MerchantAssertionCodec.encodeCoverage(wrongOrigin);
        assertThat(fixture.verifier.verifyCoverage(
                fixture.sign(wrongOriginBytes, fixture.coverageKey, fixture.coveragePair, "coverage-1", NOW,
                        COVERAGE_PURPOSE, AUDIENCE), wrongOriginBytes, fixture.origin, null))
                .isEqualTo(Denied.COVERAGE_CHAIN_MISMATCH);

        MerchantCoverageAssertion wrongPopulation = fixture.coverageWithRules("9".repeat(64));
        byte[] wrongPopulationBytes = MerchantAssertionCodec.encodeCoverage(wrongPopulation);
        assertThat(fixture.verifier.verifyCoverage(
                fixture.sign(wrongPopulationBytes, fixture.coverageKey, fixture.coveragePair, "coverage-1", NOW,
                        COVERAGE_PURPOSE, AUDIENCE), wrongPopulationBytes, fixture.origin, null))
                .isEqualTo(Denied.POPULATION_NOT_AUTHORIZED);
    }

    @Test
    void capabilitiesAndClaimsRedactSensitiveValuesAndDefendTheirByteCopies() {
        Fixture fixture = new Fixture();
        byte[] original = MerchantAssertionCodec.encodeObservation(fixture.observation());
        VerifiedObservation verified = (VerifiedObservation) fixture.verifier.verifyObservation(
                fixture.sign(original, fixture.observationKey, fixture.observationPair, SOURCE_EVENT, NOW,
                        OBSERVATION_PURPOSE, AUDIENCE), original, fixture.expected);
        byte[] returned = verified.originalAssertionBytes();
        returned[0] = ' ';
        assertThat(verified.originalAssertionBytes()).containsExactly(original);
        assertThat(verified.toString()).doesNotContain(MERCHANT_PSEUDONYM, SOURCE_EVENT, SOURCE_ISSUER);
        assertThat(verified.assertion().toString()).doesNotContain(MERCHANT_PSEUDONYM, SOURCE_EVENT, SOURCE_ISSUER);
        assertThat(fixture.expected.toString()).doesNotContain(MERCHANT_PSEUDONYM, SOURCE_EVENT, SOURCE_ISSUER);
    }

    private static final class Fixture {
        private final KeyPair sourcePair = pair();
        private final KeyPair observationPair = pair();
        private final KeyPair coveragePair = pair();
        private final SourceVerificationProperties.TrustedKey sourceKey = key(SOURCE_ISSUER, "source-v1",
                TRANSACTION_PURPOSE, SOURCE_AUDIENCE, sourcePair, NOW.minusSeconds(86_400), NOW.plusSeconds(86_400), false);
        private final SourceVerificationProperties sourcePolicy = new SourceVerificationProperties(List.of(sourceKey));
        private final SourceIdentity sourceIdentity = new SourceIdentity(SOURCE_ISSUER, INSTITUTION, SOURCE, EPOCH, 1);
        private final Population population = new Population("merchant-feed-v1", "all-merchant-events", 1,
                POPULATION_COMMITMENT, INCLUSION_COMMITMENT, EXCLUSION_COMMITMENT, NOW.minusSeconds(30L * 86_400));
        private final SourceVerificationProperties.TrustedKey observationKey = key("merchant-observer", "observation-v1",
                OBSERVATION_PURPOSE, AUDIENCE, observationPair, NOW.minusSeconds(86_400), NOW.plusSeconds(86_400), false);
        private final SourceVerificationProperties.TrustedKey coverageKey = key("merchant-coverage", "coverage-v1",
                COVERAGE_PURPOSE, AUDIENCE, coveragePair, NOW.minusSeconds(86_400), NOW.plusSeconds(86_400), false);
        private final Grant observationGrant = new Grant(Role.OBSERVATION, OBSERVATION_GRANT_ID, 1,
                observationKey, sourceIdentity, population,
                new MerchantMapping(1, MAPPING_COMMITMENT));
        private final Grant coverageGrant = new Grant(Role.COVERAGE, COVERAGE_GRANT_ID, 1,
                coverageKey, sourceIdentity, population, null);
        private final SourceScope sourceScope = new SourceScope(SOURCE_ISSUER, INSTITUTION, SOURCE, EPOCH,
                "source-v1", SOURCE_EVENT, NOW, TRANSACTION_PURPOSE, SOURCE_AUDIENCE,
                SourceSignedInput.payloadCommitment("{\"transaction\":true}".getBytes(StandardCharsets.UTF_8)));
        private final SourceSignatureVerifier.Verified origin = (SourceSignatureVerifier.Verified)
                new SourceSignatureVerifier(sourcePolicy, CLOCK).verify(
                        MerchantAssertionVerifierTest.sign(sourceScope, "{\"transaction\":true}".getBytes(StandardCharsets.UTF_8), sourcePair),
                        "{\"transaction\":true}".getBytes(StandardCharsets.UTF_8),
                        new SourceSignatureVerifier.Context(TRANSACTION_PURPOSE, SOURCE_AUDIENCE));
        private final ExpectedSourceBinding expected = new ExpectedSourceBinding(origin,
                SourceReceiptIdentity.identity(sourceScope), RAW_COMMITMENT, ENRICHED_COMMITMENT,
                TRANSACTION_COMMITMENT, OCCURRENCE_COMMITMENT, MERCHANT_PSEUDONYM, 1, MAPPING_COMMITMENT);
        private final MerchantAssertionVerifier verifier = verifier(List.of(observationGrant, coverageGrant));

        MerchantAssertionVerifier verifier(List<Grant> grants) { return new MerchantAssertionVerifier(sourcePolicy, grants, CLOCK); }

        MerchantObservationAssertion observation() {
            return observationWithGrant(OBSERVATION_GRANT_ID, 1);
        }

        MerchantObservationAssertion observationWithGrant(String grantId, int grantVersion) {
            return observationWithPolicy(grantId, grantVersion, "policy-v1");
        }

        MerchantObservationAssertion observationWithPolicy(String policyVersion) {
            return observationWithPolicy(OBSERVATION_GRANT_ID, 1, policyVersion);
        }

        private MerchantObservationAssertion observationWithPolicy(String grantId, int grantVersion, String policyVersion) {
            return new MerchantObservationAssertion(1, SOURCE_ISSUER, INSTITUTION, SOURCE, EPOCH, grantId, grantVersion,
                    policyVersion, 1, SOURCE_EVENT, TRANSACTION_COMMITMENT, OCCURRENCE_COMMITMENT, SourceReceiptIdentity.identity(sourceScope),
                    RAW_COMMITMENT, ENRICHED_COMMITMENT, MERCHANT_PSEUDONYM, 1, MAPPING_COMMITMENT,
                    population.feedId(), population.id(), population.definitionVersion(), POPULATION_COMMITMENT, 1, NOW, NOW);
        }

        MerchantObservationAssertion observationWithSourceCommitments(String transaction, String occurrence) {
            return new MerchantObservationAssertion(1, SOURCE_ISSUER, INSTITUTION, SOURCE, EPOCH,
                    OBSERVATION_GRANT_ID, 1, "policy-v1", 1, SOURCE_EVENT, transaction, occurrence,
                    SourceReceiptIdentity.identity(sourceScope), RAW_COMMITMENT, ENRICHED_COMMITMENT,
                    MERCHANT_PSEUDONYM, 1, MAPPING_COMMITMENT, population.feedId(), population.id(),
                    population.definitionVersion(), POPULATION_COMMITMENT, 1, NOW, NOW);
        }

        MerchantObservationAssertion observation(String sourceIssuer, String institution, String source, String epoch,
                                                 String merchantPseudonym, int mappingVersion,
                                                 String populationId, int populationVersion) {
            return new MerchantObservationAssertion(1, sourceIssuer, institution, source, epoch, OBSERVATION_GRANT_ID, 1,
                    "policy-v1", 1, SOURCE_EVENT, TRANSACTION_COMMITMENT, OCCURRENCE_COMMITMENT, SourceReceiptIdentity.identity(sourceScope),
                    RAW_COMMITMENT, ENRICHED_COMMITMENT, merchantPseudonym, mappingVersion, MAPPING_COMMITMENT,
                    population.feedId(), populationId, populationVersion, POPULATION_COMMITMENT, 1, NOW, NOW);
        }

        MerchantCoverageAssertion coverage(long generation, long from, long through, String previous, Instant watermark) {
            return new MerchantCoverageAssertion(1, SOURCE_ISSUER, INSTITUTION, SOURCE, EPOCH,
                    COVERAGE_GRANT_ID, 1, "policy-v1", 1,
                    "coverage-" + generation, population.feedId(), population.id(), population.definitionVersion(),
                    population.definitionCommitment(), population.inclusionRulesCommitment(), population.exclusionRulesCommitment(),
                    1, from, through, NOW.minusSeconds(86_400), watermark, generation, previous);
        }

        MerchantCoverageAssertion coverageWithRules(String inclusionCommitment) {
            return new MerchantCoverageAssertion(1, SOURCE_ISSUER, INSTITUTION, SOURCE, EPOCH,
                    COVERAGE_GRANT_ID, 1, "policy-v1", 1, "coverage-1", population.feedId(), population.id(),
                    population.definitionVersion(), population.definitionCommitment(), inclusionCommitment,
                    population.exclusionRulesCommitment(), 1, 1, 1, NOW.minusSeconds(86_400), NOW, 1, null);
        }

        Result verifyObservation(byte[] payload) {
            return verifier.verifyObservation(sign(payload, observationKey, observationPair, SOURCE_EVENT, NOW,
                    OBSERVATION_PURPOSE, AUDIENCE), payload, expected);
        }

        byte[] sign(byte[] payload, SourceVerificationProperties.TrustedKey key, KeyPair pair,
                    String eventId, Instant eventTime, String purpose, String audience) {
            SourceScope scope = new SourceScope(key.issuer(), key.institution(), key.source(), key.sourceEpoch(),
                    key.signingKeyVersion(), eventId, eventTime, purpose, audience,
                    SourceSignedInput.payloadCommitment(payload));
            return MerchantAssertionVerifierTest.sign(scope, payload, pair);
        }

        Grant observationGrant(KeyPair pair, boolean revoked, Instant notBefore, Instant notAfter) {
            SourceVerificationProperties.TrustedKey key = key("merchant-observer", "observation-v1",
                    OBSERVATION_PURPOSE, AUDIENCE, pair, notBefore, notAfter, revoked);
            return new Grant(Role.OBSERVATION, OBSERVATION_GRANT_ID, 1, key, sourceIdentity, population,
                    new MerchantMapping(1, MAPPING_COMMITMENT));
        }

        SourceVerificationProperties.TrustedKey key(String issuer, String version, String purpose, String audience,
                                                    KeyPair pair, Instant notBefore, Instant notAfter, boolean revoked) {
            return new SourceVerificationProperties.TrustedKey(issuer, INSTITUTION, SOURCE, EPOCH, version,
                    "Ed25519", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                    notBefore, notAfter, revoked, purpose, audience, "policy-v1");
        }
    }

    private static byte[] sign(SourceScope scope, byte[] payload, KeyPair key) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key.getPrivate());
            signer.update(SourceSignedInput.encode(scope, payload));
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
            String proof = "{\"protocolVersion\":1,\"algorithm\":\"Ed25519\",\"issuer\":\"" + scope.issuer()
                    + "\",\"institution\":\"" + scope.institution() + "\",\"source\":\"" + scope.source()
                    + "\",\"sourceEpoch\":\"" + scope.sourceEpoch() + "\",\"signingKeyVersion\":\""
                    + scope.signingKeyVersion() + "\",\"sourceEventId\":\"" + scope.sourceEventId()
                    + "\",\"eventTime\":\"" + scope.eventTime() + "\",\"purpose\":\"" + scope.purpose()
                    + "\",\"audience\":\"" + scope.audience() + "\",\"payloadCommitment\":\""
                    + scope.payloadCommitment() + "\",\"signature\":\"" + encoded + "\"}";
            return proof.getBytes(StandardCharsets.UTF_8);
        } catch (Exception unavailable) {
            throw new IllegalStateException("TEST_SIGNER_UNAVAILABLE");
        }
    }

    private static KeyPair pair() {
        try { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch (Exception unavailable) { throw new IllegalStateException("TEST_KEY_UNAVAILABLE"); }
    }
}
