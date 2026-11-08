package com.frauddetection.ingest.source;

import com.frauddetection.common.events.source.SourceScope;
import com.frauddetection.common.events.source.SourceSignedInput;
import com.frauddetection.common.events.source.SourceVerificationProperties;
import com.frauddetection.common.events.source.SourceSignatureVerifier;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static com.frauddetection.common.events.source.SourceSignatureVerifier.Denied.*;
import static com.frauddetection.ingest.source.TestSourceIssuer.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceSignatureVerifierTest {
    private static final SourceSignatureVerifier.Context CONTEXT = new SourceSignatureVerifier.Context(PURPOSE, AUDIENCE);

    @Test void verifiesRealSignatureAndReturnsOnlyBoundedImmutableEvidence() throws Exception {
        var result = verifier(policy(0, false)).verify(proof(), PAYLOAD, CONTEXT);
        assertThat(result).isInstanceOf(SourceSignatureVerifier.Verified.class);
        var verified = (SourceSignatureVerifier.Verified) result;
        assertThat(verified.scope().issuer()).isEqualTo(ISSUER);
        assertThat(verified.scope().sourceEventId()).isEqualTo("source-event-42");
        assertThat(verified.policyVersion()).isEqualTo("policy-1");
        assertThat(verified.verifiedAt()).isEqualTo(NOW);
        assertThat(verified.toString()).isEqualTo("VerifiedSourceEvidence[redacted]");
        assertThat(verified.scope().toString()).isEqualTo("SourceScope[redacted]");
        assertThat(proof()).isEqualTo(proof());
        assertThat(SourceSignedInput.encode(verified.scope(), PAYLOAD))
                .isEqualTo(signedBytes(claims(PAYLOAD), PAYLOAD));
    }

    @Test void wrongSignerCannotUseAnAllowedKeyId() throws Exception {
        assertThat(verifier(policy(0, false)).verify(proof(PAYLOAD, Map.of(), 1), PAYLOAD, CONTEXT))
                .isEqualTo(INVALID_SIGNATURE);
    }

    @ParameterizedTest @ValueSource(strings = {"issuer", "sourceEpoch", "signingKeyVersion"})
    void unknownAnchorIsNotAdmitted(String field) throws Exception {
        assertThat(verifier(policy(0, false)).verify(proof(PAYLOAD, Map.of(field, "unknown"), 0), PAYLOAD, CONTEXT))
                .isEqualTo(UNKNOWN_TRUST_ANCHOR);
    }

    @Test void changingExactPayloadBytesOrSignedClaimsFails() throws Exception {
        var verifier = verifier(policy(0, false));
        byte[] changedPayload = new String(PAYLOAD, StandardCharsets.UTF_8).replace("42.00", "43.00")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.verify(proof(), changedPayload, CONTEXT)).isEqualTo(PAYLOAD_COMMITMENT_MISMATCH);
        byte[] changedCommitment = new String(proof(), StandardCharsets.UTF_8)
                .replace(SourceSignedInput.payloadCommitment(PAYLOAD), SourceSignedInput.payloadCommitment(changedPayload))
                .getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.verify(changedCommitment, changedPayload, CONTEXT)).isEqualTo(INVALID_SIGNATURE);
        byte[] changedClaim = new String(proof(), StandardCharsets.UTF_8).replace("institution-a", "institution-b")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.verify(changedClaim, PAYLOAD, CONTEXT)).isEqualTo(INVALID_SIGNATURE);
    }

    @ParameterizedTest @ValueSource(strings = {"institution", "source"})
    void authenticSignatureDoesNotAuthorizeAnotherInstitutionOrSource(String field) throws Exception {
        assertThat(verifier(policy(0, false)).verify(proof(PAYLOAD, Map.of(field, "other"), 0), PAYLOAD, CONTEXT))
                .isEqualTo(SOURCE_SCOPE_NOT_AUTHORIZED);
    }

    @Test void signatureIdentityAndContextAuthorizationAreSeparate() throws Exception {
        var verifier = verifier(policy(0, false));
        assertThat(verifier.verify(proof(PAYLOAD, Map.of("purpose", "GOVERNANCE"), 0), PAYLOAD, CONTEXT))
                .isEqualTo(PURPOSE_NOT_AUTHORIZED);
        assertThat(verifier.verify(proof(PAYLOAD, Map.of("audience", "other-service"), 0), PAYLOAD, CONTEXT))
                .isEqualTo(AUDIENCE_NOT_AUTHORIZED);
        assertThat(verifier.verify(proof(), PAYLOAD, new SourceSignatureVerifier.Context("GOVERNANCE", AUDIENCE)))
                .isEqualTo(PURPOSE_NOT_AUTHORIZED);
        assertThat(verifier.verify(proof(), PAYLOAD, new SourceSignatureVerifier.Context(PURPOSE, "other-service")))
                .isEqualTo(AUDIENCE_NOT_AUTHORIZED);
        assertThat(verifier.verify(proof(), PAYLOAD, null)).isEqualTo(CONTEXT_NOT_AUTHORIZED);
    }

    @Test void revokedExpiredFutureAndWrongEventEpochsAreDenied() throws Exception {
        assertThat(verifier(policy(0, true)).verify(proof(), PAYLOAD, CONTEXT)).isEqualTo(TRUST_ANCHOR_REVOKED);
        assertThat(new SourceSignatureVerifier(new SourceVerificationProperties(List.of(policy(0, false))),
                Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC)).verify(proof(), PAYLOAD, CONTEXT))
                .isEqualTo(EPOCH_INACTIVE);
        assertThat(new SourceSignatureVerifier(new SourceVerificationProperties(List.of(policy(0, false))),
                Clock.fixed(NOW.minusSeconds(3601), ZoneOffset.UTC)).verify(proof(), PAYLOAD, CONTEXT))
                .isEqualTo(EPOCH_INACTIVE);
        for (var eventTime : List.of(NOW.minusSeconds(3601), NOW.plusSeconds(3600), NOW.plusSeconds(301))) {
            assertThat(verifier(policy(0, false)).verify(proof(PAYLOAD, Map.of("eventTime", eventTime.toString()), 0),
                    PAYLOAD, CONTEXT)).isEqualTo(EPOCH_INACTIVE);
        }
    }

    @Test void rotationUsesOnlyTheConfiguredEffectiveKeyVersion() throws Exception {
        var verifier = verifier(policy(0, true), policy(1, false));
        assertThat(verifier.verify(proof(), PAYLOAD, CONTEXT)).isEqualTo(TRUST_ANCHOR_REVOKED);
        assertThat(verifier.verify(proof(PAYLOAD, Map.of("signingKeyVersion", "key-2"), 1), PAYLOAD, CONTEXT))
                .isInstanceOf(SourceSignatureVerifier.Verified.class);
        assertThat(verifier(policy(0, false)).verify(proof(PAYLOAD, Map.of("signingKeyVersion", "key-2"), 1),
                PAYLOAD, CONTEXT)).isEqualTo(UNKNOWN_TRUST_ANCHOR);
    }

    @Test void currentPolicyRejectsVerificationOutsideItsEffectiveKeyEpoch() throws Exception {
        byte[] signed = proof(PAYLOAD, Map.of("eventTime", NOW.plusSeconds(60).toString()), 0);
        var earlier = (SourceSignatureVerifier.Verified) verifier(policy(0, false)).verify(signed, PAYLOAD, CONTEXT);
        var original = policy(0, false);
        var narrowed = new SourceVerificationProperties.TrustedKey(original.issuer(), original.institution(), original.source(),
                original.sourceEpoch(), original.signingKeyVersion(), original.algorithm(), original.publicKeyBase64(),
                NOW.plusSeconds(30), NOW.plusSeconds(150), false, PURPOSE, AUDIENCE, original.policyVersion());
        var current = new SourceSignatureVerifier(new SourceVerificationProperties(List.of(narrowed)),
                Clock.fixed(NOW.plusSeconds(120), ZoneOffset.UTC));
        assertThat(current.verify(signed, PAYLOAD, CONTEXT)).isInstanceOf(SourceSignatureVerifier.Verified.class);
        assertThat(current.currentDenial(earlier)).isEqualTo(EPOCH_INACTIVE);
        assertThat(current.receiptPolicyDenial(earlier.scope(), earlier.keyCommitment(), earlier.policyVersion(), narrowed.notAfter()))
                .isEqualTo(EPOCH_INACTIVE);
    }

    @ParameterizedTest @ValueSource(strings = {"none", "HS256", "RSA", "ed25519"})
    void algorithmsCannotBeNegotiatedByTheCaller(String algorithm) throws Exception {
        assertThat(verifier(policy(0, false)).verify(proof(PAYLOAD, Map.of("algorithm", algorithm), 0), PAYLOAD, CONTEXT))
                .isEqualTo(UNSUPPORTED_ALGORITHM);
    }

    @ParameterizedTest @ValueSource(ints = {0, 2, -1})
    void unsupportedProtocolsCannotDowngradeVerification(int version) throws Exception {
        assertThat(verifier(policy(0, false)).verify(proof(PAYLOAD, Map.of("protocolVersion", version), 0),
                PAYLOAD, CONTEXT)).isEqualTo(UNSUPPORTED_PROTOCOL);
    }

    @Test void rejectsAmbiguousMalformedAndCallerSuppliedAuthorityFields() throws Exception {
        String valid = new String(proof(), StandardCharsets.UTF_8);
        List<String> malformed = List.of("[]", "{}", valid + " {}", valid.substring(0, valid.length() - 1),
                valid.replace("\"protocolVersion\":1", "\"protocolVersion\":\"1\""),
                valid.replace("\"protocolVersion\":1", "\"protocolVersion\":1.0"),
                valid.replace("\"issuer\":", "\"issuer\":\"duplicate\",\"issuer\":"),
                valid.replace("\"issuer\":", "\"issu\\u0065r\":\"duplicate\",\"issuer\":"),
                valid.replaceFirst("\\{", "{\"publicKey\":\"caller-supplied\","),
                valid.replaceFirst("\\{", "{\"keyUri\":\"https://caller.invalid/key\","),
                valid.replace("2026-11-08T09:59:50Z", "2026-11-08T09:59:50.000Z"),
                valid.replace("source-event-42", "source event"),
                valid.replace("\"signature\":\"", "\"signature\":\"="));
        for (String candidate : malformed) {
            assertThat(verifier(policy(0, false)).verify(candidate.getBytes(StandardCharsets.UTF_8), PAYLOAD, CONTEXT))
                    .as("malformed envelope is denied without reflecting its content").isEqualTo(MALFORMED_PROOF);
        }
        assertThat(verifier(policy(0, false)).verify(new byte[] {(byte) 0xc3, (byte) 0x28}, PAYLOAD, CONTEXT))
                .isEqualTo(MALFORMED_PROOF);
    }

    @Test void capsInputWorkBeforeParsingOrCrypto() throws Exception {
        var verifier = verifier();
        assertThat(verifier.verify(new byte[SourceSignedInput.MAX_PROOF_BYTES + 1], PAYLOAD, CONTEXT))
                .isEqualTo(PROOF_TOO_LARGE);
        assertThat(verifier.verify(proof(), new byte[SourceSignedInput.MAX_PAYLOAD_BYTES + 1], CONTEXT))
                .isEqualTo(PAYLOAD_TOO_LARGE);
        assertThat(verifier.verify(null, PAYLOAD, CONTEXT)).isEqualTo(PROOF_ABSENT);
        assertThat(verifier.verify(proof(), null, CONTEXT)).isEqualTo(PAYLOAD_ABSENT);
        assertThat(verifier.verify(proof(), PAYLOAD, CONTEXT)).isEqualTo(UNKNOWN_TRUST_ANCHOR);
        assertThatThrownBy(() -> new SourceVerificationProperties(java.util.Collections.nCopies(65, policy(0, false))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("SOURCE_TRUST_POLICY_CAPACITY_EXCEEDED");
    }

    @Test void conflictingOrMalformedIndependentConfigurationFailsStartup() {
        assertThatThrownBy(() -> verifier(policy(0, false), policy(0, false)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("SOURCE_TRUST_POLICY_INVALID");
        var valid = policy(0, false);
        assertThatThrownBy(() -> verifier(new SourceVerificationProperties.TrustedKey(valid.issuer(), valid.institution(),
                valid.source(), valid.sourceEpoch(), valid.signingKeyVersion(), "Ed25519", "not-a-key",
                valid.notBefore(), valid.notAfter(), false, PURPOSE, AUDIENCE, "policy-1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("SOURCE_TRUST_POLICY_INVALID");
    }

    private SourceSignatureVerifier verifier(SourceVerificationProperties.TrustedKey... policies) {
        return new SourceSignatureVerifier(new SourceVerificationProperties(List.of(policies)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
