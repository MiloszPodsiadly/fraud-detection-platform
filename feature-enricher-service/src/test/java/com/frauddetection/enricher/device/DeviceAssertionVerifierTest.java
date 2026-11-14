package com.frauddetection.enricher.device;

import com.frauddetection.common.events.source.SourceReceiptIdentity;
import com.frauddetection.common.events.source.SourceScope;
import com.frauddetection.common.events.source.SourceSignatureVerifier;
import com.frauddetection.common.events.source.SourceSignedInput;
import com.frauddetection.common.events.source.SourceVerificationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.frauddetection.enricher.device.DeviceAssertionVerifier.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Disposable UNTRUSTED_TEST_ONLY keys; these tests prove proof verification, not source truth or admission. */
class DeviceAssertionVerifierTest {
    private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String RAW = "a".repeat(64), ENRICHED = "b".repeat(64), CUSTOMER = "c".repeat(64),
            DEVICE = "d".repeat(64), TRANSACTION = "e".repeat(64), DEFINITION = "f".repeat(64);

    @Test void independentlySignedObservationAndCoverageVerifyWithoutGrantingHistoryCompleteness() {
        Fixture f = new Fixture();
        var observation = (VerifiedObservation) f.observation(f.observation());
        var coverage = (VerifiedCoverage) f.coverage(f.coverage());
        assertThat(observation.claims().receiptId()).isEqualTo(f.binding.receiptId());
        assertThat(observation.claims().rawCommitment()).isEqualTo(RAW).isNotEqualTo(f.origin.scope().payloadCommitment());
        assertThat(observation.payloadCommitment()).isEqualTo(SourceSignedInput.payloadCommitment(bytes(f.observation())));
        assertThat(coverage.claims().throughSequence()).isEqualTo(1);
        assertThat(f.verifier.currentDenial(observation)).isNull();
        assertThat(f.verifier.currentDenial(coverage)).isNull();
        assertThat(observation.toString()).isEqualTo("VerifiedDeviceAssertion[redacted]");
        assertThat(coverage.claims().toString()).isEqualTo("DeviceCoverageAssertion[redacted]");
    }

    @Test void originalSignedBytesAreAuthoritativeAndDtoReserializationDoesNotRepairTheSignature() {
        Fixture f = new Fixture();
        byte[] original = bytes(f.observation());
        byte[] proof = f.proof(original, Role.OBSERVATION);
        byte[] reformatted = (new String(original, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8);
        assertThat(f.verifier.verifyObservation(proof, reformatted, f.binding)).isEqualTo(Denied.SIGNATURE_OR_POLICY_DENIED);
        byte[] changed = original.clone();
        changed[changed.length / 2] ^= 1;
        assertThat(f.verifier.verifyObservation(proof, changed, f.binding)).isEqualTo(Denied.SIGNATURE_OR_POLICY_DENIED);
        assertThat(f.verifier.verifyObservation(proof, original, f.binding)).isInstanceOf(VerifiedObservation.class);
    }

    @Test void genericTransactionSourceProofCannotAuthorizeDeviceObservationOrCoverage() {
        Fixture f = new Fixture();
        for (Role role : Role.values()) {
            byte[] payload = bytes(role == Role.OBSERVATION ? f.observation() : f.coverage());
            SourceScope scope = f.scope(f.originKey, payload, "source-event-1", NOW);
            byte[] proof = sign(scope, payload, f.originPair);
            assertThat(role == Role.OBSERVATION ? f.verifier.verifyObservation(proof, payload, f.binding)
                    : f.verifier.verifyCoverage(proof, payload, f.origin)).isEqualTo(Denied.SIGNATURE_OR_POLICY_DENIED);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "institution", "source", "sourceEpoch", "signingKeyVersion", "purpose", "audience"})
    void callerSelectedEnvelopeScopeCannotEscalateDeviceAuthority(String field) {
        Fixture f = new Fixture();
        byte[] payload = bytes(f.observation());
        Map<String, Object> envelope = envelope(f.scope(f.observationGrant.key(), payload, "source-event-1", NOW));
        envelope.put(field, "wrong-private-scope");
        assertThat(f.verifier.verifyObservation(sign(sourceScope(envelope), payload, f.observationPair), payload, f.binding))
                .isEqualTo(Denied.SIGNATURE_OR_POLICY_DENIED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sourceIssuer", "institution", "source", "sourceEpoch", "sourceEventId", "receiptId",
            "rawCommitment", "enrichedCommitment", "transactionKey", "customerToken", "deviceToken"})
    void evenValidDeviceSignatureCannotOverrideExpectedSourceAndCustomerDeviceBinding(String field) {
        Fixture f = new Fixture();
        Map<String, Object> claim = f.observation();
        claim.put(field, field.endsWith("Commitment") || field.endsWith("Token") || field.endsWith("Key") || field.equals("receiptId")
                ? "9".repeat(64) : "wrong-private-scope");
        assertThat(f.observation(claim)).isEqualTo(Denied.BINDING_MISMATCH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bindingAuthority", "providerBasis", "policyVersion"})
    void assertionMustUseExplicitlyAuthorizedBindingAuthorityAndProviderBasis(String field) {
        Fixture f = new Fixture();
        Map<String, Object> claim = f.observation();
        claim.put(field, "unknown-authority");
        assertThat(f.observation(claim)).isEqualTo(Denied.AUTHORITY_NOT_AUTHORIZED);
    }

    @Test void identicalReplayVerifiesButConflictingPayloadCannotReplaceTheExpectedBinding() {
        Fixture f = new Fixture();
        assertThat(f.observation(f.observation())).isInstanceOf(VerifiedObservation.class);
        assertThat(f.observation(f.observation())).isInstanceOf(VerifiedObservation.class);
        Map<String, Object> conflict = f.observation();
        conflict.put("deviceToken", "8".repeat(64));
        assertThat(f.observation(conflict)).isEqualTo(Denied.BINDING_MISMATCH);
        // Durable uniqueness, sequence monotonicity and one-effect replay are P03 ledger responsibilities.
    }

    @Test void signingKeyMismatchExpiryRevocationAndUnknownKeyAllDeny() {
        Fixture f = new Fixture();
        byte[] payload = bytes(f.observation());
        assertThat(f.verifier.verifyObservation(sign(f.scope(f.observationGrant.key(), payload, "source-event-1", NOW),
                payload, pair()), payload, f.binding)).isEqualTo(Denied.SIGNATURE_OR_POLICY_DENIED);
        for (String mutation : List.of("expired", "future", "revoked", "unknown")) {
            var key = mutate(f.observationGrant.key(), mutation);
            DeviceAssertionVerifier verifier = f.verifier(List.of(f.withKey(f.observationGrant, key), f.coverageGrant), CLOCK);
            assertThat(verifier.verifyObservation(f.proof(payload, Role.OBSERVATION), payload, f.binding))
                    .isEqualTo(Denied.SIGNATURE_OR_POLICY_DENIED);
        }
    }

    @Test void sourceRevocationAndMissingSourceEvidenceDenyBeforeDeviceVerification() {
        Fixture f = new Fixture();
        var revokedOrigin = new SourceVerificationProperties(List.of(mutate(f.originKey, "revoked")));
        var verifier = new DeviceAssertionVerifier(revokedOrigin, List.of(f.observationGrant, f.coverageGrant), CLOCK);
        byte[] payload = bytes(f.observation());
        assertThat(verifier.verifyObservation(f.proof(payload, Role.OBSERVATION), payload, f.binding)).isEqualTo(Denied.SOURCE_NOT_AUTHORIZED);
        assertThat(f.verifier.verifyObservation(null, payload, null)).isEqualTo(Denied.SOURCE_NOT_AUTHORIZED);
        assertThat(f.verifier.verifyCoverage(null, bytes(f.coverage()), null)).isEqualTo(Denied.SOURCE_NOT_AUTHORIZED);
    }

    @Test void genericRightsAndReusedSourceOrCrossPurposeKeyCannotBeConfiguredAsDeviceTrust() {
        Fixture f = new Fixture();
        assertThatThrownBy(() -> f.withKey(f.observationGrant, f.originKey)).isInstanceOf(IllegalArgumentException.class);
        var sameOriginMaterial = replaceMaterial(f.observationGrant.key(), f.originKey.publicKeyBase64());
        assertThatThrownBy(() -> f.verifier(List.of(f.withKey(f.observationGrant, sameOriginMaterial)), CLOCK))
                .hasMessage("DEVICE_ASSERTION_POLICY_INVALID");
        var sameObservationMaterial = replaceMaterial(f.coverageGrant.key(), f.observationGrant.key().publicKeyBase64());
        assertThatThrownBy(() -> f.verifier(List.of(f.observationGrant, f.withKey(f.coverageGrant, sameObservationMaterial)), CLOCK))
                .hasMessage("DEVICE_ASSERTION_POLICY_INVALID");
        assertThat(f.verifier(List.of(), CLOCK).verifyObservation(f.proof(bytes(f.observation()), Role.OBSERVATION), bytes(f.observation()), f.binding))
                .isEqualTo(Denied.SIGNATURE_OR_POLICY_DENIED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"populationId", "populationDefinitionVersion", "populationDefinitionCommitment"})
    void observationAndCoverageRejectUnknownPopulationEvenWithAValidSignature(String field) {
        Fixture f = new Fixture();
        for (Role role : Role.values()) {
            Map<String, Object> claim = role == Role.OBSERVATION ? f.observation() : f.coverage();
            claim.put(field, field.endsWith("Commitment") ? "7".repeat(64) : "unknown-population");
            assertThat(role == Role.OBSERVATION ? f.observation(claim) : f.coverage(claim)).isEqualTo(Denied.POPULATION_NOT_AUTHORIZED);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"inclusionRule", "exclusionRule", "sequenceOrigin", "coverageStart"})
    void coverageCannotBroadenTheAuthorizedPopulationOrItsHistoricalInterval(String field) {
        Fixture f = new Fixture();
        Map<String, Object> claim = f.coverage();
        claim.put(field, switch (field) {
            case "sequenceOrigin" -> 2L;
            case "coverageStart" -> NOW.minusSeconds(31L * 86_400).toString();
            default -> "unknown-rule";
        });
        Result result = f.coverage(claim);
        assertThat(result).isIn(Denied.POPULATION_NOT_AUTHORIZED, Denied.MALFORMED_ASSERTION);
    }

    @Test void coverageRangeGenerationAndClockMustBeCompatible() {
        Fixture f = new Fixture();
        for (Map<String, Object> patch : List.<Map<String, Object>>of(Map.of("fromSequence", 2), Map.of("throughSequence", 0),
                Map.of("coverageGeneration", 2), Map.of("previousCoverageCommitment", "1".repeat(64)),
                Map.of("coverageStart", NOW.toString()), Map.of("sequenceOrigin", 0))) {
            Map<String, Object> claim = f.coverage(); claim.putAll(patch);
            assertThat(f.coverage(claim)).isEqualTo(Denied.MALFORMED_ASSERTION);
        }
        Map<String, Object> future = f.coverage(); future.put("watermark", NOW.plusSeconds(121).toString());
        assertThat(f.coverage(future)).isEqualTo(Denied.TIME_NOT_AUTHORIZED);
    }

    @Test void observationCannotPrecedeTheExplicitlyAuthorizedSequenceOrigin() {
        Fixture f = new Fixture();
        Population laterOrigin = new Population(f.population.id(), f.population.definitionVersion(), f.population.definitionCommitment(),
                f.population.inclusionRule(), f.population.exclusionRule(), 10, f.population.earliestCoverageStart());
        Grant grant = new Grant(Role.OBSERVATION, f.observationGrant.key(), f.scope, laterOrigin, 1, "binding-v1", "institution-observation-v1");
        byte[] payload = bytes(f.observation());
        assertThat(f.verifier(List.of(grant), CLOCK).verifyObservation(f.proof(payload, Role.OBSERVATION), payload, f.binding))
                .isEqualTo(Denied.POPULATION_NOT_AUTHORIZED);
    }

    @Test void coverageRequiresItsOwnSigningRoleAndCompletePopulationDefinition() {
        Fixture f = new Fixture();
        byte[] payload = bytes(f.coverage());
        assertThat(f.verifier.verifyCoverage(f.proof(payload, Role.OBSERVATION), payload, f.origin))
                .isEqualTo(Denied.SIGNATURE_OR_POLICY_DENIED);
        for (String field : List.of("populationId", "populationDefinitionVersion", "populationDefinitionCommitment", "inclusionRule", "exclusionRule")) {
            Map<String, Object> claim = f.coverage(); claim.remove(field);
            assertThat(f.coverage(claim)).isEqualTo(Denied.MALFORMED_ASSERTION);
        }
        for (String field : List.of("sourceIssuer", "institution", "source", "sourceEpoch", "coverageId", "policyVersion")) {
            Map<String, Object> claim = f.coverage(); claim.put(field, "wrong-private-scope");
            assertThat(f.coverage(claim)).isEqualTo(Denied.BINDING_MISMATCH);
        }
    }

    @Test void pseudonymRotationNeedsIndependentAuthorizationAndDoesNotFollowSigningKeyRotation() {
        Fixture f = new Fixture();
        for (Role role : Role.values()) {
            Map<String, Object> claim = role == Role.OBSERVATION ? f.observation() : f.coverage();
            claim.put("pseudonymKeyVersion", 2);
            assertThat(role == Role.OBSERVATION ? f.observation(claim) : f.coverage(claim)).isEqualTo(Denied.PSEUDONYM_KEY_NOT_AUTHORIZED);
        }
        KeyPair rotatedPair = pair();
        var rotatedKey = f.key("device-issuer", "signing-v2", Role.OBSERVATION.purpose, rotatedPair);
        var verifier = f.verifier(List.of(f.observationGrant, f.withKey(f.observationGrant, rotatedKey), f.coverageGrant), CLOCK);
        byte[] payload = bytes(f.observation());
        assertThat(verifier.verifyObservation(sign(f.scope(rotatedKey, payload, "source-event-1", NOW), payload, rotatedPair), payload, f.binding))
                .isInstanceOf(VerifiedObservation.class);
    }

    @Test void currentPolicyRecheckRevokesPreviouslyVerifiedEvidenceAndChangedPopulationGrant() {
        Fixture f = new Fixture();
        Verified evidence = (Verified) f.observation(f.observation());
        var revoked = f.withKey(f.observationGrant, mutate(f.observationGrant.key(), "revoked"));
        assertThat(f.verifier(List.of(revoked, f.coverageGrant), CLOCK).currentDenial(evidence)).isEqualTo(Denied.POLICY_CHANGED);
        Grant changed = new Grant(Role.OBSERVATION, f.observationGrant.key(), f.scope, f.population, 2, "binding-v1", "institution-observation-v1");
        assertThat(f.verifier(List.of(changed, f.coverageGrant), CLOCK).currentDenial(evidence)).isEqualTo(Denied.POLICY_CHANGED);
        assertThat(f.verifier(List.of(f.observationGrant, f.coverageGrant), Clock.fixed(NOW.plusSeconds(3_600), ZoneOffset.UTC))
                .currentDenial(evidence)).isEqualTo(Denied.POLICY_CHANGED);
    }

    @Test void observationClockBoundsAndExactEnvelopeTimeAreEnforced() {
        Fixture f = new Fixture();
        for (String time : List.of(NOW.minusSeconds(301).toString(), NOW.plusSeconds(121).toString())) {
            Map<String, Object> claim = f.observation(); claim.put("observedAt", time);
            assertThat(f.observation(claim)).isEqualTo(Denied.TIME_NOT_AUTHORIZED);
        }
        byte[] payload = bytes(f.observation());
        assertThat(f.verifier.verifyObservation(sign(f.scope(f.observationGrant.key(), payload, "source-event-1", NOW.minusSeconds(1)),
                payload, f.observationPair), payload, f.binding)).isEqualTo(Denied.BINDING_MISMATCH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "unknown", "missing", "nested", "trailing", "utf8", "string-number", "fraction",
            "overflow", "zero-sequence", "time-precision", "noncanonical-time", "version", "oversized-string"})
    void signedMalformedOrAmbiguousPayloadsAreRejected(String mutation) {
        Fixture f = new Fixture();
        Map<String, Object> claim = f.observation();
        String valid = new String(bytes(claim), StandardCharsets.UTF_8);
        byte[] payload;
        switch (mutation) {
            case "duplicate" -> payload = (valid.substring(0, valid.length() - 1) + ",\"sourceSequence\":1}").getBytes(StandardCharsets.UTF_8);
            case "unknown" -> { claim.put("unknownCriticalClaim", true); payload = bytes(claim); }
            case "missing" -> { claim.remove("receiptId"); payload = bytes(claim); }
            case "nested" -> { claim.put("providerBasis", Map.of("a", Map.of("b", Map.of("c", "x")))); payload = bytes(claim); }
            case "trailing" -> payload = (valid + " {}").getBytes(StandardCharsets.UTF_8);
            case "utf8" -> payload = new byte[]{(byte) 0xc3, (byte) 0x28};
            case "string-number" -> { claim.put("sourceSequence", "1"); payload = bytes(claim); }
            case "fraction" -> { claim.put("sourceSequence", 1.0); payload = bytes(claim); }
            case "overflow" -> payload = valid.replace("\"sourceSequence\":1", "\"sourceSequence\":9223372036854775808").getBytes(StandardCharsets.UTF_8);
            case "zero-sequence" -> { claim.put("sourceSequence", 0); payload = bytes(claim); }
            case "time-precision" -> { claim.put("observedAt", NOW.plusNanos(1).toString()); payload = bytes(claim); }
            case "noncanonical-time" -> { claim.put("observedAt", "2026-11-12T12:00:00+00:00"); payload = bytes(claim); }
            case "version" -> { claim.put("assertionVersion", 2); payload = bytes(claim); }
            case "oversized-string" -> { claim.put("providerBasis", "x".repeat(129)); payload = bytes(claim); }
            default -> throw new IllegalStateException();
        }
        assertThat(f.verifier.verifyObservation(f.proof(payload, Role.OBSERVATION), payload, f.binding)).isEqualTo(Denied.MALFORMED_ASSERTION);
    }

    @Test void envelopeBoundsDuplicateFieldsVersionAndSignatureEncodingFailClosed() {
        Fixture f = new Fixture();
        byte[] payload = bytes(f.observation());
        String valid = new String(f.proof(payload, Role.OBSERVATION), StandardCharsets.UTF_8);
        for (byte[] proof : List.of(new byte[4_097], (valid.substring(0, valid.length() - 1) + ",\"protocolVersion\":1}").getBytes(StandardCharsets.UTF_8),
                valid.replace("\"protocolVersion\":1", "\"protocolVersion\":2").getBytes(StandardCharsets.UTF_8),
                valid.replace("\"algorithm\":\"Ed25519\"", "\"algorithm\":\"RSA\"").getBytes(StandardCharsets.UTF_8))) {
            assertThat(f.verifier.verifyObservation(proof, payload, f.binding)).isEqualTo(Denied.SIGNATURE_OR_POLICY_DENIED);
        }
        Map<String, Object> badEncoding = envelope(f.scope(f.observationGrant.key(), payload, "source-event-1", NOW));
        badEncoding.put("signature", "A".repeat(86) + "==");
        assertThat(f.verifier.verifyObservation(bytes(badEncoding), payload, f.binding)).isEqualTo(Denied.SIGNATURE_OR_POLICY_DENIED);
        assertThat(f.verifier.verifyObservation(null, payload, f.binding)).isEqualTo(Denied.SIGNATURE_OR_POLICY_DENIED);
        assertThat(f.verifier.verifyObservation(null, new byte[8_193], f.binding)).isEqualTo(Denied.PAYLOAD_REJECTED);
        assertThat(f.verifier.verifyObservation(null, null, f.binding)).isEqualTo(Denied.PAYLOAD_REJECTED);
    }

    @Test void claimsBindingsAndPoliciesHaveBoundedRedactedErrorAndStringSurfaces() {
        Fixture f = new Fixture();
        VerifiedObservation observation = (VerifiedObservation) f.observation(f.observation());
        for (Object value : List.of(f.binding, f.scope, f.population, f.observationGrant, observation, observation.claims())) {
            assertThat(value.toString()).doesNotContain(CUSTOMER, DEVICE, f.binding.receiptId(), "origin-issuer");
        }
        assertThatThrownBy(() -> DeviceAssertions.observation("{\"secret\":\"private-data\"}".getBytes(StandardCharsets.UTF_8)))
                .hasMessage("DEVICE_ASSERTION_MALFORMED");
    }

    private static final class Fixture {
        final KeyPair originPair = pair(), observationPair = pair(), coveragePair = pair();
        final SourceVerificationProperties.TrustedKey originKey = key("origin-issuer", "origin-v1", TRANSACTION_PURPOSE, originPair);
        final SourceVerificationProperties originPolicy = new SourceVerificationProperties(List.of(originKey));
        final DeviceAssertions.Scope scope = new DeviceAssertions.Scope("origin-issuer", "institution-1", "source-1", "epoch-1");
        final Population population = new Population("device-events", "definition-v1", DEFINITION,
                "ALL_INSTITUTION_DEVICE_OBSERVATIONS", "NONE", 1, NOW.minusSeconds(30L * 86_400));
        final Grant observationGrant = new Grant(Role.OBSERVATION, key("device-issuer", "device-v1", Role.OBSERVATION.purpose, observationPair),
                scope, population, 1, "binding-v1", "institution-observation-v1");
        final Grant coverageGrant = new Grant(Role.COVERAGE, key("coverage-issuer", "coverage-v1", Role.COVERAGE.purpose, coveragePair),
                scope, population, 1, "binding-v1", "institution-observation-v1");
        final SourceSignatureVerifier.Verified origin;
        final ExpectedBinding binding;
        final DeviceAssertionVerifier verifier;
        Fixture() {
            byte[] originalSourcePayload = "{\"untrustedTestOnly\":true}".getBytes(StandardCharsets.UTF_8);
            origin = (SourceSignatureVerifier.Verified) new SourceSignatureVerifier(originPolicy, CLOCK).verify(
                    sign(scope(originKey, originalSourcePayload, "source-event-1", NOW), originalSourcePayload, originPair),
                    originalSourcePayload, new SourceSignatureVerifier.Context(TRANSACTION_PURPOSE, "transaction-ingest"));
            binding = new ExpectedBinding(origin, SourceReceiptIdentity.identity(origin.scope()), RAW, ENRICHED, TRANSACTION, CUSTOMER, DEVICE, 1);
            verifier = verifier(List.of(observationGrant, coverageGrant), CLOCK);
        }
        DeviceAssertionVerifier verifier(List<Grant> grants, Clock clock) { return new DeviceAssertionVerifier(originPolicy, grants, clock); }
        Grant withKey(Grant grant, SourceVerificationProperties.TrustedKey key) {
            return new Grant(grant.role(), key, grant.sourceScope(), grant.population(), grant.pseudonymKeyVersion(), grant.bindingAuthority(), grant.providerBasis());
        }
        SourceVerificationProperties.TrustedKey key(String issuer, String version, String purpose, KeyPair pair) {
            return new SourceVerificationProperties.TrustedKey(issuer, "institution-1", "source-1", "epoch-1", version,
                    "Ed25519", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()), NOW.minusSeconds(90L * 86_400),
                    NOW.plusSeconds(purpose.equals(TRANSACTION_PURPOSE) ? 86_400 : 600), false, purpose,
                    purpose.equals(TRANSACTION_PURPOSE) ? "transaction-ingest" : AUDIENCE, "policy-v1");
        }
        SourceScope scope(SourceVerificationProperties.TrustedKey key, byte[] payload, String eventId, Instant time) {
            return new SourceScope(key.issuer(), key.institution(), key.source(), key.sourceEpoch(), key.signingKeyVersion(),
                    eventId, time, key.purpose(), key.audience(), SourceSignedInput.payloadCommitment(payload));
        }
        byte[] proof(byte[] payload, Role role) {
            Grant grant = role == Role.OBSERVATION ? observationGrant : coverageGrant;
            return sign(scope(grant.key(), payload, role == Role.OBSERVATION ? "source-event-1" : "coverage-1",
                    role == Role.OBSERVATION ? NOW : watermark(payload)), payload, role == Role.OBSERVATION ? observationPair : coveragePair);
        }
        Result observation(Map<String, Object> claim) { byte[] payload = bytes(claim); return verifier.verifyObservation(proof(payload, Role.OBSERVATION), payload, binding); }
        Result coverage(Map<String, Object> claim) { byte[] payload = bytes(claim); return verifier.verifyCoverage(proof(payload, Role.COVERAGE), payload, origin); }
        Map<String, Object> base() {
            Map<String, Object> claim = new LinkedHashMap<>();
            claim.put("assertionVersion", 1); claim.put("sourceIssuer", scope.sourceIssuer()); claim.put("institution", scope.institution());
            claim.put("source", scope.source()); claim.put("sourceEpoch", scope.sourceEpoch()); claim.put("populationId", population.id());
            claim.put("populationDefinitionVersion", population.definitionVersion()); claim.put("populationDefinitionCommitment", population.definitionCommitment());
            claim.put("policyVersion", "policy-v1"); claim.put("pseudonymKeyVersion", 1); return claim;
        }
        Map<String, Object> observation() {
            Map<String, Object> claim = base();
            claim.put("sourceEventId", "source-event-1"); claim.put("transactionKey", TRANSACTION); claim.put("receiptId", binding.receiptId());
            claim.put("rawCommitment", RAW); claim.put("enrichedCommitment", ENRICHED); claim.put("customerToken", CUSTOMER);
            claim.put("deviceToken", DEVICE); claim.put("sourceSequence", 1); claim.put("eventTime", NOW.toString());
            claim.put("observedAt", NOW.toString()); claim.put("bindingAuthority", "binding-v1"); claim.put("providerBasis", "institution-observation-v1"); return claim;
        }
        Map<String, Object> coverage() {
            Map<String, Object> claim = base();
            claim.put("coverageId", "coverage-1"); claim.put("inclusionRule", population.inclusionRule()); claim.put("exclusionRule", population.exclusionRule());
            claim.put("sequenceOrigin", 1); claim.put("fromSequence", 1); claim.put("throughSequence", 1);
            claim.put("coverageStart", population.earliestCoverageStart().toString()); claim.put("watermark", NOW.toString());
            claim.put("coverageGeneration", 1); claim.put("previousCoverageCommitment", "0".repeat(64)); claim.put("coverageCommitment", "1".repeat(64)); return claim;
        }
    }

    private static Instant watermark(byte[] payload) {
        try { return Instant.parse(JSON.readTree(payload).get("watermark").textValue()); }
        catch (Exception invalidFixture) { throw new IllegalArgumentException("INVALID_TEST_COVERAGE"); }
    }
    private static KeyPair pair() {
        try { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch (Exception unavailable) { throw new IllegalStateException("TEST_KEY_UNAVAILABLE"); }
    }
    private static byte[] bytes(Object value) { return JSON.writeValueAsBytes(value); }
    private static byte[] sign(SourceScope scope, byte[] payload, KeyPair key) {
        try {
            Signature signer = Signature.getInstance("Ed25519"); signer.initSign(key.getPrivate()); signer.update(SourceSignedInput.encode(scope, payload));
            Map<String, Object> proof = envelope(scope); proof.put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())); return bytes(proof);
        } catch (Exception unavailable) { throw new IllegalStateException("TEST_SIGNER_UNAVAILABLE"); }
    }
    private static Map<String, Object> envelope(SourceScope scope) {
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("protocolVersion", 1); proof.put("algorithm", "Ed25519"); proof.put("issuer", scope.issuer()); proof.put("institution", scope.institution());
        proof.put("source", scope.source()); proof.put("sourceEpoch", scope.sourceEpoch()); proof.put("signingKeyVersion", scope.signingKeyVersion());
        proof.put("sourceEventId", scope.sourceEventId()); proof.put("eventTime", scope.eventTime().toString()); proof.put("purpose", scope.purpose());
        proof.put("audience", scope.audience()); proof.put("payloadCommitment", scope.payloadCommitment()); return proof;
    }
    private static SourceScope sourceScope(Map<String, Object> envelope) {
        return new SourceScope((String) envelope.get("issuer"), (String) envelope.get("institution"), (String) envelope.get("source"),
                (String) envelope.get("sourceEpoch"), (String) envelope.get("signingKeyVersion"), (String) envelope.get("sourceEventId"),
                Instant.parse((String) envelope.get("eventTime")), (String) envelope.get("purpose"), (String) envelope.get("audience"),
                (String) envelope.get("payloadCommitment"));
    }
    private static SourceVerificationProperties.TrustedKey mutate(SourceVerificationProperties.TrustedKey key, String mutation) {
        return new SourceVerificationProperties.TrustedKey(key.issuer(), key.institution(), key.source(), key.sourceEpoch(),
                mutation.equals("unknown") ? "new-key" : key.signingKeyVersion(), key.algorithm(), key.publicKeyBase64(),
                mutation.equals("future") ? NOW.plusSeconds(1) : key.notBefore(), mutation.equals("expired") ? NOW.minusSeconds(1) : key.notAfter(),
                mutation.equals("revoked"), key.purpose(), key.audience(), key.policyVersion());
    }
    private static SourceVerificationProperties.TrustedKey replaceMaterial(SourceVerificationProperties.TrustedKey key, String material) {
        return new SourceVerificationProperties.TrustedKey(key.issuer(), key.institution(), key.source(), key.sourceEpoch(), key.signingKeyVersion(),
                key.algorithm(), material, key.notBefore(), key.notAfter(), key.revoked(), key.purpose(), key.audience(), key.policyVersion());
    }
}
