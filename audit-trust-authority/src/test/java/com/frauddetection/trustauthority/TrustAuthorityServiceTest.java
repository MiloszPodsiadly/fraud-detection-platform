package com.frauddetection.trustauthority;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustAuthorityServiceTest {

    @Test
    void shouldSignAndVerifyAuditAnchorPayloadHash() {
        TrustAuthorityService service = service("key-1");
        TrustSignRequest request = request("hash-1", "anchor-1");

        TrustSignResponse signature = service.sign(request);
        TrustVerifyResponse verified = service.verify(new TrustVerifyRequest(
                request.purpose(),
                request.payloadHash(),
                request.partitionKey(),
                request.chainPosition(),
                request.anchorId(),
                signature.signature(),
                signature.keyId()
        ));

        assertThat(signature.algorithm()).isEqualTo("Ed25519");
        assertThat(verified.status()).isEqualTo("VALID");
    }

    @Test
    void shouldRejectModifiedPayloadHash() {
        TrustAuthorityService service = service("key-1");
        TrustSignResponse signature = service.sign(request("hash-1", "anchor-1"));

        TrustVerifyResponse verified = service.verify(new TrustVerifyRequest(
                "AUDIT_ANCHOR",
                "hash-2",
                "source_service:alert-service",
                1L,
                "anchor-1",
                signature.signature(),
                signature.keyId()
        ));

        assertThat(verified.status()).isEqualTo("INVALID");
    }

    @Test
    void shouldRejectWrongPublicKeyByKeyId() {
        TrustAuthorityService service = service("key-1");
        TrustSignResponse signature = service.sign(request("hash-1", "anchor-1"));

        TrustVerifyResponse verified = service.verify(new TrustVerifyRequest(
                "AUDIT_ANCHOR",
                "hash-1",
                "source_service:alert-service",
                1L,
                "anchor-1",
                signature.signature(),
                "unknown-key"
        ));

        assertThat(verified.status()).isEqualTo("INVALID");
        assertThat(verified.reasonCode()).isEqualTo("UNKNOWN_KEY");
    }

    @Test
    void shouldRejectMissingKeyId() {
        TrustAuthorityService service = service("key-1");
        TrustSignResponse signature = service.sign(request("hash-1", "anchor-1"));

        TrustVerifyResponse verified = service.verify(new TrustVerifyRequest(
                "AUDIT_ANCHOR",
                "hash-1",
                "source_service:alert-service",
                1L,
                "anchor-1",
                signature.signature(),
                null
        ));

        assertThat(verified.status()).isEqualTo("INVALID");
        assertThat(verified.reasonCode()).isEqualTo("UNKNOWN_KEY");
    }

    @Test
    void shouldVerifyRetiredKeyAfterRotationToNewActiveKey() throws Exception {
        KeyPair keyV1 = keyPair();
        KeyPair keyV2 = keyPair();
        TrustAuthorityService oldService = service(propertiesWithKeys(
                key("key-v1", "ACTIVE", keyV1)
        ));
        TrustSignRequest request = request("hash-1", "anchor-1");
        TrustSignResponse oldSignature = oldService.sign(request);

        TrustAuthorityService rotatedService = service(propertiesWithKeys(
                key("key-v1", "RETIRED", keyV1),
                key("key-v2", "ACTIVE", keyV2)
        ));
        TrustVerifyResponse verified = rotatedService.verify(new TrustVerifyRequest(
                request.purpose(),
                request.payloadHash(),
                request.partitionKey(),
                request.chainPosition(),
                request.anchorId(),
                oldSignature.signature(),
                oldSignature.keyId()
        ));
        TrustSignResponse newSignature = rotatedService.sign(request);

        assertThat(verified.status()).isEqualTo("VALID");
        assertThat(newSignature.keyId()).isEqualTo("key-v2");
        assertThat(rotatedService.keys()).extracting(TrustKeyResponse::keyId)
                .containsExactly("key-v1", "key-v2");
    }

    @Test
    void shouldRejectRevokedKeyAndUnknownKey() throws Exception {
        KeyPair keyV1 = keyPair();
        TrustAuthorityService oldService = service(propertiesWithKeys(key("key-v1", "ACTIVE", keyV1)));
        TrustSignRequest request = request("hash-1", "anchor-1");
        TrustSignResponse signature = oldService.sign(request);
        TrustAuthorityService revokedService = service(propertiesWithKeys(
                key("key-v1", "REVOKED", keyV1),
                key("key-v2", "ACTIVE", keyPair())
        ));

        TrustVerifyResponse revoked = revokedService.verify(new TrustVerifyRequest(
                request.purpose(), request.payloadHash(), request.partitionKey(), request.chainPosition(),
                request.anchorId(), signature.signature(), "key-v1"
        ));
        TrustVerifyResponse unknown = revokedService.verify(new TrustVerifyRequest(
                request.purpose(), request.payloadHash(), request.partitionKey(), request.chainPosition(),
                request.anchorId(), signature.signature(), "missing-key"
        ));

        assertThat(revoked.status()).isEqualTo("INVALID");
        assertThat(revoked.reasonCode()).isEqualTo("KEY_REVOKED");
        assertThat(unknown.status()).isEqualTo("INVALID");
        assertThat(unknown.reasonCode()).isEqualTo("UNKNOWN_KEY");
    }

    @Test
    void shouldAllowDefaultTokenInLocalProfile() throws Exception {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        TrustAuthorityRuntimeGuard guard = new TrustAuthorityRuntimeGuard(properties, environment("local"));

        guard.run(null);
    }

    @Test
    void shouldRejectDefaultTokenInProdLikeProfile() {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        TrustAuthorityRuntimeGuard guard = new TrustAuthorityRuntimeGuard(properties, environment("prod"));

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-default internal token");
    }

    @Test
    void shouldRejectMissingTokenInProdLikeProfile() {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        properties.setInternalToken("");
        TrustAuthorityRuntimeGuard guard = new TrustAuthorityRuntimeGuard(properties, environment("staging"));

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-default internal token");
    }

    @Test
    void shouldRejectEphemeralGeneratedKeysInProdLikeProfile() {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        properties.setInternalToken("prod-token");
        TrustAuthorityRuntimeGuard guard = new TrustAuthorityRuntimeGuard(properties, environment("production"));

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("persistent private key path");
    }

    private TrustAuthorityService service(String keyId) {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        properties.setKeyId(keyId);
        properties.setAuthorityName("local-trust-authority");
        return new TrustAuthorityService(properties);
    }

    private TrustAuthorityService service(TrustAuthorityProperties properties) {
        return new TrustAuthorityService(properties);
    }

    private TrustAuthorityProperties propertiesWithKeys(TrustAuthorityProperties.KeyEntry... keys) {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        properties.setKeyId(keys[0].getKeyId());
        properties.setKeys(List.of(keys));
        return properties;
    }

    private TrustAuthorityProperties.KeyEntry key(String keyId, String status, KeyPair keyPair) {
        TrustAuthorityProperties.KeyEntry entry = new TrustAuthorityProperties.KeyEntry();
        entry.setKeyId(keyId);
        entry.setStatus(status);
        entry.setPublicKey(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        entry.setPrivateKey(Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
        return entry;
    }

    private KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private MockEnvironment environment(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }

    private TrustSignRequest request(String payloadHash, String anchorId) {
        return new TrustSignRequest("AUDIT_ANCHOR", payloadHash, "source_service:alert-service", 1L, anchorId);
    }
}
