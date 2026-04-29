package com.frauddetection.trustauthority;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.ArrayList;
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
                signature.keyId(),
                signature.signedAt()
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
                signature.keyId(),
                signature.signedAt()
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
                "unknown-key",
                signature.signedAt()
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
                null,
                signature.signedAt()
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
                oldSignature.keyId(),
                oldSignature.signedAt()
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
                request.anchorId(), signature.signature(), "key-v1", signature.signedAt()
        ));
        TrustVerifyResponse unknown = revokedService.verify(new TrustVerifyRequest(
                request.purpose(), request.payloadHash(), request.partitionKey(), request.chainPosition(),
                request.anchorId(), signature.signature(), "missing-key", signature.signedAt()
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
        properties.setCallers(List.of(caller("alert-service", "alert-token", List.of("AUDIT_ANCHOR"))));
        TrustAuthorityRuntimeGuard guard = new TrustAuthorityRuntimeGuard(properties, environment("production"));

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("persistent private key path");
    }

    @Test
    void shouldRejectImplicitCallerAllowlistInProdLikeProfile() {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        properties.setInternalToken("prod-token");
        properties.setPrivateKeyPath("private.key");
        properties.setPublicKeyPath("public.key");
        TrustAuthorityRuntimeGuard guard = new TrustAuthorityRuntimeGuard(properties, environment("prod"));

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicit caller allowlist");
    }

    @Test
    void shouldAuditSignAndVerifyBeforeReturning() {
        RecordingAuditSink auditSink = new RecordingAuditSink();
        TrustAuthorityService service = service(new TrustAuthorityProperties(), auditSink);
        TrustSignRequest request = request("hash-1", "anchor-1");

        TrustSignResponse signature = service.sign(request);
        TrustVerifyResponse verified = service.verify(new TrustVerifyRequest(
                request.purpose(), request.payloadHash(), request.partitionKey(), request.chainPosition(),
                request.anchorId(), signature.signature(), signature.keyId(), signature.signedAt()
        ));

        assertThat(verified.status()).isEqualTo("VALID");
        assertThat(auditSink.events()).extracting(TrustAuthorityAuditEvent::action)
                .containsExactly("SIGN", "VERIFY");
        assertThat(auditSink.events()).extracting(TrustAuthorityAuditEvent::result)
                .containsExactly("SUCCESS", "SUCCESS");
        assertThat(auditSink.events()).extracting(TrustAuthorityAuditEvent::payloadHash)
                .containsExactly("hash-1", "hash-1");
    }

    @Test
    void shouldFailRequestWhenAuditWriteFails() {
        TrustAuthorityService service = service(new TrustAuthorityProperties(), event -> {
            throw new TrustAuthorityAuditException("failed", null);
        });

        assertThatThrownBy(() -> service.sign(request("hash-1", "anchor-1")))
                .isInstanceOf(TrustAuthorityAuditException.class);
    }

    @Test
    void shouldRejectUnauthorizedPurposeForScoringCallerAndAuditFailure() {
        RecordingAuditSink auditSink = new RecordingAuditSink();
        TrustAuthorityService service = service(new TrustAuthorityProperties(), auditSink);

        assertThatThrownBy(() -> service.sign(
                "local-dev-trust-token",
                TrustAuthorityCallerIdentity.of("fraud-scoring-service", "local", "score-1"),
                request("hash-1", "anchor-1")
        )).isInstanceOf(TrustAuthorityRequestException.class);

        assertThat(auditSink.events()).hasSize(1);
        assertThat(auditSink.events().getFirst().result()).isEqualTo("FAILURE");
        assertThat(auditSink.events().getFirst().reasonCode()).isEqualTo("PURPOSE_UNAUTHORIZED");
    }

    @Test
    void shouldRateLimitSignPerCallerAndAuditRejection() {
        RecordingAuditSink auditSink = new RecordingAuditSink();
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        TrustAuthorityProperties.CallerEntry caller = new TrustAuthorityProperties.CallerEntry();
        caller.setServiceName("alert-service");
        caller.setInternalToken("token-1");
        caller.setAllowedPurposes(List.of("AUDIT_ANCHOR"));
        caller.setSignRateLimitPerMinute(1);
        properties.setCallers(List.of(caller));
        TrustAuthorityService service = service(properties, auditSink);

        service.sign("token-1", TrustAuthorityCallerIdentity.of("alert-service", "local", "a"), request("hash-1", "anchor-1"));

        assertThatThrownBy(() -> service.sign("token-1", TrustAuthorityCallerIdentity.of("alert-service", "local", "a"), request("hash-2", "anchor-2")))
                .isInstanceOf(TrustAuthorityRequestException.class);
        assertThat(auditSink.events()).extracting(TrustAuthorityAuditEvent::reasonCode)
                .containsExactly(null, "RATE_LIMIT_EXCEEDED");
    }

    @Test
    void shouldEnforceKeyValidityWindowDuringVerification() throws Exception {
        KeyPair keyPair = keyPair();
        TrustAuthorityProperties.KeyEntry key = key("key-v1", "ACTIVE", keyPair);
        key.setValidFrom(Instant.parse("2026-04-01T00:00:00Z"));
        key.setValidUntil(Instant.parse("2026-04-30T00:00:00Z"));
        TrustAuthorityService service = service(propertiesWithKeys(key));
        TrustSignRequest request = request("hash-1", "anchor-1");
        TrustSignResponse signature = service.sign(request);

        TrustVerifyResponse expired = service.verify(new TrustVerifyRequest(
                request.purpose(), request.payloadHash(), request.partitionKey(), request.chainPosition(),
                request.anchorId(), signature.signature(), signature.keyId(), Instant.parse("2026-05-01T00:00:00Z")
        ));
        TrustVerifyResponse valid = service.verify(new TrustVerifyRequest(
                request.purpose(), request.payloadHash(), request.partitionKey(), request.chainPosition(),
                request.anchorId(), signature.signature(), signature.keyId(), Instant.parse("2026-04-15T00:00:00Z")
        ));

        assertThat(expired.status()).isEqualTo("INVALID");
        assertThat(expired.reasonCode()).isEqualTo("KEY_EXPIRED");
        assertThat(valid.status()).isEqualTo("VALID");
    }

    @Test
    void shouldRejectDisabledSigningRequiredInProdLikeProfile() {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        properties.setInternalToken("prod-token");
        properties.setPrivateKeyPath("private.key");
        properties.setPublicKeyPath("public.key");
        properties.setSigningRequired(false);
        properties.setCallers(List.of(caller("alert-service", "alert-token", List.of("AUDIT_ANCHOR"))));
        TrustAuthorityRuntimeGuard guard = new TrustAuthorityRuntimeGuard(properties, environment("prod"));

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signing-required=true");
    }

    private TrustAuthorityService service(String keyId) {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        properties.setKeyId(keyId);
        properties.setAuthorityName("local-trust-authority");
        return service(properties, new RecordingAuditSink());
    }

    private TrustAuthorityService service(TrustAuthorityProperties properties, TrustAuthorityAuditSink auditSink) {
        return new TrustAuthorityService(properties, auditSink, new TrustAuthorityRateLimiter());
    }

    private TrustAuthorityService service(TrustAuthorityProperties properties) {
        return service(properties, new RecordingAuditSink());
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

    private TrustAuthorityProperties.CallerEntry caller(String serviceName, String token, List<String> purposes) {
        TrustAuthorityProperties.CallerEntry caller = new TrustAuthorityProperties.CallerEntry();
        caller.setServiceName(serviceName);
        caller.setInternalToken(token);
        caller.setAllowedPurposes(purposes);
        return caller;
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

    private static class RecordingAuditSink implements TrustAuthorityAuditSink {
        private final List<TrustAuthorityAuditEvent> events = new ArrayList<>();

        @Override
        public void append(TrustAuthorityAuditEvent event) {
            events.add(event);
        }

        List<TrustAuthorityAuditEvent> events() {
            return events;
        }
    }
}
