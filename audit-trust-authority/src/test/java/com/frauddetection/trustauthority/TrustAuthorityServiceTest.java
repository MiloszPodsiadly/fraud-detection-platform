package com.frauddetection.trustauthority;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
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

        TrustSignResponse signature = sign(service, request);
        TrustVerifyResponse verified = verify(service, new TrustVerifyRequest(
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
        TrustSignResponse signature = sign(service, request("hash-1", "anchor-1"));

        TrustVerifyResponse verified = verify(service, new TrustVerifyRequest(
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
        TrustSignResponse signature = sign(service, request("hash-1", "anchor-1"));

        TrustVerifyResponse verified = verify(service, new TrustVerifyRequest(
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
        TrustSignResponse signature = sign(service, request("hash-1", "anchor-1"));

        TrustVerifyResponse verified = verify(service, new TrustVerifyRequest(
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
        TrustSignResponse oldSignature = sign(oldService, request);

        TrustAuthorityService rotatedService = service(propertiesWithKeys(
                key("key-v1", "RETIRED", keyV1),
                key("key-v2", "ACTIVE", keyV2)
        ));
        TrustVerifyResponse verified = verify(rotatedService, new TrustVerifyRequest(
                request.purpose(),
                request.payloadHash(),
                request.partitionKey(),
                request.chainPosition(),
                request.anchorId(),
                oldSignature.signature(),
                oldSignature.keyId(),
                oldSignature.signedAt()
        ));
        TrustSignResponse newSignature = sign(rotatedService, request);

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
        TrustSignResponse signature = sign(oldService, request);
        TrustAuthorityService revokedService = service(propertiesWithKeys(
                key("key-v1", "REVOKED", keyV1),
                key("key-v2", "ACTIVE", keyPair())
        ));

        TrustVerifyResponse revoked = verify(revokedService, new TrustVerifyRequest(
                request.purpose(), request.payloadHash(), request.partitionKey(), request.chainPosition(),
                request.anchorId(), signature.signature(), "key-v1", signature.signedAt()
        ));
        TrustVerifyResponse unknown = verify(revokedService, new TrustVerifyRequest(
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
        properties.setAllowLocalHmacInProd(true);
        properties.getAudit().setSink("durable-append-only");
        TrustAuthorityRuntimeGuard guard = new TrustAuthorityRuntimeGuard(properties, environment("prod"));

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-default HMAC secret");
    }

    @Test
    void shouldRejectMissingHmacSecretInProdLikeProfile() {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        properties.setHmacSecret("");
        properties.setAllowLocalHmacInProd(true);
        properties.getAudit().setSink("durable-append-only");
        TrustAuthorityRuntimeGuard guard = new TrustAuthorityRuntimeGuard(properties, environment("staging"));

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-default HMAC secret");
    }

    @Test
    void shouldRejectEphemeralGeneratedKeysInProdLikeProfile() {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        properties.setHmacSecret("prod-secret");
        properties.setAllowLocalHmacInProd(true);
        properties.getAudit().setSink("durable-append-only");
        properties.setCallers(List.of(caller("alert-service", "alert-secret", List.of("AUDIT_ANCHOR"))));
        TrustAuthorityRuntimeGuard guard = new TrustAuthorityRuntimeGuard(properties, environment("production"));

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("persistent private key path");
    }

    @Test
    void shouldRejectImplicitCallerAllowlistInProdLikeProfile() {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        properties.setHmacSecret("prod-secret");
        properties.setAllowLocalHmacInProd(true);
        properties.getAudit().setSink("durable-append-only");
        properties.setPrivateKeyPath("private.key");
        properties.setPublicKeyPath("public.key");
        TrustAuthorityRuntimeGuard guard = new TrustAuthorityRuntimeGuard(properties, environment("prod"));

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicit caller allowlist");
    }

    @Test
    void shouldRejectLocalFileAuditSinkInProdLikeProfile() {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        properties.setHmacSecret("prod-secret");
        properties.setAllowLocalHmacInProd(true);
        properties.getAudit().setSink("local-file");
        TrustAuthorityRuntimeGuard guard = new TrustAuthorityRuntimeGuard(properties, environment("prod"));

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durable-append-only audit sink");
    }

    @Test
    void shouldFailClosedForUnimplementedEnterpriseIdentityModesInProdLikeProfile() {
        TrustAuthorityProperties mtlsProperties = new TrustAuthorityProperties();
        mtlsProperties.setIdentityMode("mtls-ready");
        TrustAuthorityRuntimeGuard mtlsGuard = new TrustAuthorityRuntimeGuard(mtlsProperties, environment("prod"));
        TrustAuthorityProperties jwtProperties = new TrustAuthorityProperties();
        jwtProperties.setIdentityMode("jwt-ready");
        TrustAuthorityRuntimeGuard jwtGuard = new TrustAuthorityRuntimeGuard(jwtProperties, environment("production"));

        assertThatThrownBy(() -> mtlsGuard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not implemented and fails closed");
        assertThatThrownBy(() -> jwtGuard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not implemented and fails closed");
    }

    @Test
    void shouldRejectInlinePrivateKeyMaterialInProdLikeProfile() throws Exception {
        TrustAuthorityProperties properties = propertiesWithKeys(key("key-v1", "ACTIVE", keyPair()));
        properties.setHmacSecret("prod-secret");
        properties.setAllowLocalHmacInProd(true);
        properties.getAudit().setSink("durable-append-only");
        properties.setCallers(List.of(caller("alert-service", "alert-secret", List.of("AUDIT_ANCHOR"))));
        TrustAuthorityRuntimeGuard guard = new TrustAuthorityRuntimeGuard(properties, environment("prod"));

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forbids inline private key material");
    }

    @Test
    void shouldExposePublicKeyFingerprintWithoutPrivateMaterial() {
        TrustAuthorityService service = service("key-1");

        TrustKeyResponse key = service.keys().getFirst();

        assertThat(key.keyFingerprintSha256()).matches("[0-9a-f]{64}");
        assertThat(key.publicKey()).isNotBlank();
        assertThat(key.publicKey()).doesNotContain("PRIVATE");
    }

    @Test
    void shouldAuditSignAndVerifyBeforeReturning() {
        RecordingAuditSink auditSink = new RecordingAuditSink();
        TrustAuthorityService service = service(new TrustAuthorityProperties(), auditSink);
        TrustSignRequest request = request("hash-1", "anchor-1");

        TrustSignResponse signature = sign(service, request);
        TrustVerifyResponse verified = verify(service, new TrustVerifyRequest(
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
    void shouldUseMongoCompatibleMillisecondAuditTimestamps() {
        RecordingAuditSink auditSink = new RecordingAuditSink();
        TrustAuthorityService service = service(new TrustAuthorityProperties(), auditSink);

        sign(service, request("hash-1", "anchor-1"));

        assertThat(auditSink.events().getFirst().occurredAt().getNano() % 1_000_000).isZero();
    }

    @Test
    void shouldFailRequestWhenAuditWriteFails() {
        TrustAuthorityService service = service(new TrustAuthorityProperties(), new TrustAuthorityAuditSink() {
            @Override
            public void append(TrustAuthorityAuditEvent event) {
                throw new TrustAuthorityAuditException("failed", null);
            }

            @Override
            public TrustAuthorityAuditIntegrityResponse integrity(int limit) {
                return TrustAuthorityAuditIntegrityResponse.unavailable("failed");
            }
        });

        TrustSignRequest request = request("hash-1", "anchor-1");
        assertThatThrownBy(() -> sign(service, request))
                .isInstanceOf(TrustAuthorityAuditException.class);
    }

    @Test
    void shouldRejectUnauthorizedPurposeForScoringCallerAndAuditFailure() {
        RecordingAuditSink auditSink = new RecordingAuditSink();
        TrustAuthorityService service = service(new TrustAuthorityProperties(), auditSink);
        TrustSignRequest request = request("hash-1", "anchor-1");

        assertThatThrownBy(() -> service.sign(
                credentials("fraud-scoring-service", "local", "local-dev-scoring-trust-hmac-secret", "SIGN", signCredentialPayload(request)),
                request
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
        caller.setHmacSecret("secret-1");
        caller.setAllowedPurposes(List.of("AUDIT_ANCHOR"));
        caller.setSignRateLimitPerMinute(1);
        properties.setCallers(List.of(caller));
        TrustAuthorityService service = service(properties, auditSink);
        TrustSignRequest first = request("hash-1", "anchor-1");
        TrustSignRequest second = request("hash-2", "anchor-2");

        service.sign(credentials("alert-service", "local", "secret-1", "SIGN", signCredentialPayload(first)), first);

        assertThatThrownBy(() -> service.sign(credentials("alert-service", "local", "secret-1", "SIGN", signCredentialPayload(second)), second))
                .isInstanceOf(TrustAuthorityRequestException.class);
        assertThat(auditSink.events()).extracting(TrustAuthorityAuditEvent::reasonCode)
                .containsExactly(null, "RATE_LIMIT_EXCEEDED");
    }

    @Test
    void shouldRejectHeaderOnlyIdentityWithoutHmacSignature() {
        RecordingAuditSink auditSink = new RecordingAuditSink();
        TrustAuthorityService service = service(new TrustAuthorityProperties(), auditSink);

        assertThatThrownBy(() -> service.sign(
                new TrustAuthorityRequestCredentials(TrustAuthorityCallerIdentity.of("alert-service", "local", "rotated"), "request-1", Instant.now().toString(), null),
                request("hash-1", "anchor-1")
        )).isInstanceOf(TrustAuthorityRequestException.class);

        assertThat(auditSink.events()).extracting(TrustAuthorityAuditEvent::reasonCode)
                .containsExactly("HMAC_CREDENTIALS_MISSING");
    }

    @Test
    void shouldRejectReusedRequestId() {
        RecordingAuditSink auditSink = new RecordingAuditSink();
        TrustAuthorityService service = service(new TrustAuthorityProperties(), auditSink);
        TrustSignRequest first = request("hash-1", "anchor-1");
        TrustAuthorityRequestCredentials credentials = credentials("alert-service", "local", "local-dev-trust-hmac-secret", "SIGN", signCredentialPayload(first), "request-1");

        service.sign(credentials, first);

        assertThatThrownBy(() -> service.sign(credentials, first))
                .isInstanceOf(TrustAuthorityRequestException.class);
        assertThat(auditSink.events()).extracting(TrustAuthorityAuditEvent::reasonCode)
                .containsExactly(null, "REPLAY_DETECTED");
    }

    @Test
    void shouldEnforceKeyValidityWindowDuringVerification() throws Exception {
        KeyPair keyPair = keyPair();
        TrustAuthorityProperties.KeyEntry key = key("key-v1", "ACTIVE", keyPair);
        key.setValidFrom(Instant.parse("2026-04-01T00:00:00Z"));
        key.setValidUntil(Instant.parse("2026-04-30T00:00:00Z"));
        TrustAuthorityService service = service(propertiesWithKeys(key));
        TrustSignRequest request = request("hash-1", "anchor-1");
        TrustSignResponse signature = sign(service, request);

        TrustVerifyResponse expired = verify(service, new TrustVerifyRequest(
                request.purpose(), request.payloadHash(), request.partitionKey(), request.chainPosition(),
                request.anchorId(), signature.signature(), signature.keyId(), Instant.parse("2026-05-01T00:00:00Z")
        ));
        TrustVerifyResponse valid = verify(service, new TrustVerifyRequest(
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
        properties.setHmacSecret("prod-secret");
        properties.setPrivateKeyPath("private.key");
        properties.setPublicKeyPath("public.key");
        properties.setSigningRequired(false);
        properties.setCallers(List.of(caller("alert-service", "alert-secret", List.of("AUDIT_ANCHOR"))));
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
        caller.setHmacSecret(token);
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

    private TrustSignResponse sign(TrustAuthorityService service, TrustSignRequest request) {
        return service.sign(credentials("alert-service", "local", "local-dev-trust-hmac-secret", "SIGN", signCredentialPayload(request)), request);
    }

    private TrustVerifyResponse verify(TrustAuthorityService service, TrustVerifyRequest request) {
        return service.verify(credentials("alert-service", "local", "local-dev-trust-hmac-secret", "VERIFY", verifyCredentialPayload(request)), request);
    }

    private TrustAuthorityRequestCredentials credentials(String serviceName, String environment, String secret, String action, String payload) {
        return credentials(serviceName, environment, secret, action, payload, java.util.UUID.randomUUID().toString());
    }

    private TrustAuthorityRequestCredentials credentials(String serviceName, String environment, String secret, String action, String payload, String requestId) {
        String signedAt = Instant.now().toString();
        TrustAuthorityCallerIdentity caller = TrustAuthorityCallerIdentity.of(serviceName, environment, "test-instance");
        return new TrustAuthorityRequestCredentials(caller, requestId, signedAt, hmac(secret, String.join("\n",
                action,
                serviceName,
                environment,
                requestId,
                signedAt,
                payload)));
    }

    private String signCredentialPayload(TrustSignRequest request) {
        return String.join("\n",
                request.purpose(),
                request.payloadHash(),
                request.partitionKey(),
                Long.toString(request.chainPosition()),
                request.anchorId());
    }

    private String verifyCredentialPayload(TrustVerifyRequest request) {
        return String.join("\n",
                request.purpose(),
                request.payloadHash(),
                request.partitionKey(),
                Long.toString(request.chainPosition()),
                request.anchorId(),
                request.signature(),
                request.keyId() == null ? "" : request.keyId(),
                request.signedAt() == null ? "" : request.signedAt().toString());
    }

    private String hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static class RecordingAuditSink implements TrustAuthorityAuditSink {
        private final List<TrustAuthorityAuditEvent> events = new ArrayList<>();

        @Override
        public void append(TrustAuthorityAuditEvent event) {
            events.add(event);
        }

        @Override
        public TrustAuthorityAuditIntegrityResponse integrity(int limit) {
            return TrustAuthorityAuditIntegrityVerifier.verify(events);
        }

        List<TrustAuthorityAuditEvent> events() {
            return events;
        }
    }
}
