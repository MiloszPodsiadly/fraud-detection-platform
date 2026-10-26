package com.frauddetection.alert.governance.modelpolicy;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Replays only cryptographic verification against the accepted snapshot, never a mutable credential counter. */
public final class GovernanceBoundWebAuthnProofVerifier {
    private final ObjectMapper mapper;

    public GovernanceBoundWebAuthnProofVerifier(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void verifyHistorical(GovernanceBoundWebAuthnProof proof, Map<String, Object> expectedBaseContract) {
        try {
            if (proof == null || !GovernanceBoundWebAuthnProof.VERSION.equals(proof.version())
                    || proof.namespace() == null || !proof.id().startsWith(proof.namespace() + ":")
                    || !proof.actorId().equals(PolicyGovernanceActor.identityId(proof.issuer(), proof.subject()))
                    || !proof.userPresenceRequired() || !proof.userVerificationRequired()
                    || proof.previousSignatureCount() < 0 || proof.signatureCount() < 0
                    || proof.acceptedAt().isAfter(proof.expiresAt())
                    || !proof.contractSha256().equals(GovernanceCanonicalJson.sha256(
                            proof.contractJson().getBytes(StandardCharsets.UTF_8)))) {
                throw new SecurityException("Historical governance assertion contract changed");
            }
            var contract = mapper.readTree(proof.contractJson());
            String nonce = contract.path("nonce").asText();
            if (nonce == null || nonce.isBlank() || expectedBaseContract == null
                    || !expectedBaseContract.containsKey("domain")) {
                throw new SecurityException("Historical governance action domain is missing");
            }
            Map<String, Object> expectedContract = new LinkedHashMap<>(expectedBaseContract);
            expectedContract.put("challengeId", proof.id());
            expectedContract.put("nonce", nonce);
            expectedContract.put("expiresAt", proof.expiresAt().toString());
            expectedContract.put("actorId", proof.actorId());
            expectedContract.put("issuer", proof.issuer());
            expectedContract.put("subject", proof.subject());
            if (!proof.contractJson().equals(new String(GovernanceCanonicalJson.serialize(mapper,
                    mapper.valueToTree(expectedContract)), StandardCharsets.UTF_8))) {
                throw new SecurityException("Historical governance action changed");
            }
            if (!proof.id().equals(contract.path("challengeId").asText())
                    || !proof.actorId().equals(contract.path("actorId").asText())
                    || !proof.issuer().equals(contract.path("issuer").asText())
                    || !proof.subject().equals(contract.path("subject").asText())
                    || !proof.expiresAt().toString().equals(contract.path("expiresAt").asText())) {
                throw new SecurityException("Historical governance assertion identity changed");
            }
            var request = AssertionRequest.fromJson(proof.requestJson());
            if (!proof.contractSha256().equals(request.getPublicKeyCredentialRequestOptions()
                    .getChallenge().getHex())
                    || request.getPublicKeyCredentialRequestOptions().getUserVerification()
                            .orElse(null) != UserVerificationRequirement.REQUIRED) {
                throw new SecurityException("Historical governance challenge changed");
            }
            byte[] responseBytes = Base64.getDecoder().decode(proof.assertionResponseBase64());
            if (!proof.assertionSha256().equals(GovernanceCanonicalJson.sha256(responseBytes))) {
                throw new SecurityException("Historical governance assertion bytes changed");
            }
            String responseJson = new String(responseBytes, StandardCharsets.UTF_8);
            String authenticatorData = mapper.readTree(responseJson).path("response")
                    .path("authenticatorData").asText();
            byte[] signedAuthenticatorData = Base64.getUrlDecoder().decode(authenticatorData);
            if (signedAuthenticatorData.length < 37
                    || (signedAuthenticatorData[32] & 0x01) == 0
                    || (signedAuthenticatorData[32] & 0x04) == 0) {
                throw new SecurityException("Historical governance user presence or verification is absent");
            }
            var relyingParty = RelyingParty.builder()
                    .identity(RelyingPartyIdentity.builder().id(proof.rpId())
                            .name("Model policy governance").build())
                    .credentialRepository(new SnapshotCredentialRepository(proof))
                    .origins(Set.of(proof.origin())).allowOriginPort(false)
                    .allowOriginSubdomain(false).validateSignatureCounter(true).build();
            var result = relyingParty.finishAssertion(FinishAssertionOptions.builder().request(request)
                    .response(PublicKeyCredential.parseAssertionResponseJson(responseJson)).build());
            if (!result.isSuccess() || !result.isUserVerified() || !result.isSignatureCounterValid()
                    || !proof.actorId().equals(result.getUsername())
                    || !proof.userHandle().equals(result.getUserHandle().getBase64Url())
                    || !proof.credentialId().equals(result.getCredentialId().getBase64Url())
                    || proof.signatureCount() != result.getSignatureCount()) {
                throw new SecurityException("Historical governance WebAuthn signature is invalid");
            }
        } catch (SecurityException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SecurityException("Historical governance WebAuthn proof is invalid", failure);
        }
    }

    private record SnapshotCredentialRepository(GovernanceBoundWebAuthnProof proof)
            implements CredentialRepository {
        @Override
        public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
            return proof.actorId().equals(username) ? Set.of(PublicKeyCredentialDescriptor.builder()
                    .id(bytes(proof.credentialId())).build()) : Set.of();
        }

        @Override
        public Optional<ByteArray> getUserHandleForUsername(String username) {
            return proof.actorId().equals(username) ? Optional.of(bytes(proof.userHandle()))
                    : Optional.empty();
        }

        @Override
        public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
            return proof.userHandle().equals(userHandle.getBase64Url())
                    ? Optional.of(proof.actorId()) : Optional.empty();
        }

        @Override
        public Optional<RegisteredCredential> lookup(ByteArray id, ByteArray userHandle) {
            return proof.credentialId().equals(id.getBase64Url())
                    && proof.userHandle().equals(userHandle.getBase64Url())
                    ? Optional.of(registered()) : Optional.empty();
        }

        @Override
        public Set<RegisteredCredential> lookupAll(ByteArray id) {
            return proof.credentialId().equals(id.getBase64Url()) ? Set.of(registered()) : Set.of();
        }

        private RegisteredCredential registered() {
            return RegisteredCredential.builder().credentialId(bytes(proof.credentialId()))
                    .userHandle(bytes(proof.userHandle())).publicKeyCose(bytes(proof.publicKeyCose()))
                    .signatureCount(proof.previousSignatureCount()).build();
        }

        private ByteArray bytes(String encoded) {
            return new ByteArray(Base64.getUrlDecoder().decode(encoded));
        }
    }
}
