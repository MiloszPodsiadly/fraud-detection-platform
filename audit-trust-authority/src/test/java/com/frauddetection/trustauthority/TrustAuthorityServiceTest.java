package com.frauddetection.trustauthority;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustAuthorityServiceTest {

    @TempDir
    Path tempDir;

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
        properties.getAudit().setSink("durable-hash-chain");
        TrustAuthorityRuntimeGuard guard = new TrustAuthorityRuntimeGuard(properties, environment("prod"));

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HMAC local mode is not permitted");
    }

    @Test
    void shouldRejectMissingHmacSecretInProdLikeProfile() {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        properties.setHmacSecret("");
        properties.getAudit().setSink("durable-hash-chain");
        TrustAuthorityRuntimeGuard guard = new TrustAuthorityRuntimeGuard(properties, environment("staging"));

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HMAC local mode is not permitted");
    }

    @Test
    void shouldRejectEphemeralGeneratedKeysInProdLikeProfile() {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        properties.setHmacSecret("prod-secret");
        properties.getAudit().setSink("durable-hash-chain");
        properties.setCallers(List.of(caller("alert-service", "alert-secret", List.of("AUDIT_ANCHOR"))));
        TrustAuthorityRuntimeGuard guard = new TrustAuthorityRuntimeGuard(properties, environment("production"));

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HMAC local mode is not permitted");
    }

    @Test
    void shouldRejectImplicitCallerAllowlistInProdLikeProfile() {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        properties.setHmacSecret("prod-secret");
        properties.getAudit().setSink("durable-hash-chain");
        properties.setPrivateKeyPath("private.key");
        properties.setPublicKeyPath("public.key");
        TrustAuthorityRuntimeGuard guard = new TrustAuthorityRuntimeGuard(properties, environment("prod"));

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HMAC local mode is not permitted");
    }

    @Test
    void shouldRejectLocalFileAuditSinkInProdLikeProfile() {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        properties.setHmacSecret("prod-secret");
        properties.getAudit().setSink("local-file");
        TrustAuthorityRuntimeGuard guard = new TrustAuthorityRuntimeGuard(properties, environment("prod"));

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HMAC local mode is not permitted");
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
    void shouldFailClosedForReadyIdentityModesInLocalProfile() {
        TrustAuthorityProperties mtlsProperties = new TrustAuthorityProperties();
        mtlsProperties.setIdentityMode("mtls-ready");
        TrustAuthorityProperties jwtProperties = new TrustAuthorityProperties();
        jwtProperties.setIdentityMode("jwt-ready");

        assertThatThrownBy(() -> new TrustAuthorityRuntimeGuard(mtlsProperties, environment("local")).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not implemented and fails closed");
        assertThatThrownBy(() -> new TrustAuthorityRuntimeGuard(jwtProperties, environment("test")).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not implemented and fails closed");
    }

    @Test
    void shouldRejectMissingIdentityModeInProdLikeProfile() {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        properties.setIdentityMode("");

        assertThatThrownBy(() -> new TrustAuthorityRuntimeGuard(properties, environment("prod")).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicit enterprise identity mode");
    }

    @Test
    void shouldAllowJwtServiceIdentityInProdLikeProfileWithCompleteConfig() throws Exception {
        KeyPair jwtKey = rsaKeyPair();
        TrustAuthorityProperties properties = jwtProperties(jwtKey);
        properties.getAudit().setSink("durable-hash-chain");
        configurePersistentSigningKeys(properties);

        new TrustAuthorityRuntimeGuard(properties, environment("prod")).run(null);
    }

    @Test
    void shouldFailStartupWhenCapabilityOverclaimsExternalTrust() {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        properties.setCapabilityLevel("external-anchored-trust");

        assertThatThrownBy(() -> new TrustAuthorityRuntimeGuard(properties, environment("local")).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_CRYPTOGRAPHIC_TRUST only");
    }

    @Test
    void shouldFailStartupWhenUnsupportedExternalClaimsAreEnabled() {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        MockEnvironment environment = environment("local");
        environment.setProperty("app.trust-authority.notarization.enabled", "true");

        assertThatThrownBy(() -> new TrustAuthorityRuntimeGuard(properties, environment).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notarization");
    }

    @Test
    void shouldPinJwtPublicKeyFingerprintsWhenConfigured() throws Exception {
        KeyPair jwtKey = rsaKeyPair();
        TrustAuthorityProperties properties = jwtProperties(jwtKey);
        properties.getJwtIdentity().setTrustedKeyFingerprints(List.of(fingerprint(jwtKey.getPublic().getEncoded())));
        TrustAuthorityService service = service(properties, new RecordingAuditSink());
        TrustSignRequest request = request("hash-1", "anchor-1");

        TrustSignResponse response = service.sign(jwtCredentials(jwtKey, "alert-service", "trust-authority", List.of("AUDIT_ANCHOR")), request);

        assertThat(response.keyId()).isEqualTo("local-ed25519-key-1");
    }

    @Test
    void shouldFailStartupForUntrustedJwtPublicKeyFingerprint() throws Exception {
        KeyPair jwtKey = rsaKeyPair();
        TrustAuthorityProperties properties = jwtProperties(jwtKey);
        properties.getJwtIdentity().setTrustedKeyFingerprints(List.of("0000000000000000000000000000000000000000000000000000000000000000"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThatThrownBy(() -> service(properties, new RecordingAuditSink(), new TrustAuthorityMetrics(registry)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fingerprint is not trusted");
        assertThat(registry.get("trust_jwt_key_fingerprint_mismatch_total").counter().count()).isEqualTo(1.0d);
    }

    @Test
    void shouldFailStartupWhenFingerprintPinsAreConfiguredWithoutJwtKeys() {
        TrustAuthorityProperties properties = jwtProperties(List.of(),
                caller("alert-service", "", List.of("AUDIT_ANCHOR"), List.of("jwt-key-1")));
        properties.getJwtIdentity().setTrustedKeyFingerprints(List.of("0000000000000000000000000000000000000000000000000000000000000000"));

        assertThatThrownBy(() -> service(properties, new RecordingAuditSink()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("require loaded public keys");
    }

    @Test
    void shouldRejectInlinePrivateKeyMaterialInProdLikeProfile() throws Exception {
        TrustAuthorityProperties properties = propertiesWithKeys(key("key-v1", "ACTIVE", keyPair()));
        properties.setHmacSecret("prod-secret");
        properties.getAudit().setSink("durable-hash-chain");
        properties.setCallers(List.of(caller("alert-service", "alert-secret", List.of("AUDIT_ANCHOR"))));
        TrustAuthorityRuntimeGuard guard = new TrustAuthorityRuntimeGuard(properties, environment("prod"));

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HMAC local mode is not permitted");
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

            @Override
            public TrustAuthorityAuditHeadResponse head() {
                return TrustAuthorityAuditHeadResponse.empty();
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
                new TrustAuthorityRequestCredentials(TrustAuthorityCallerIdentity.of("alert-service", "local", "rotated"), "request-1", Instant.now().toString(), null, null),
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
    void shouldRejectDistributedHintReplayFoundInAuditChain() {
        RecordingAuditSink auditSink = new RecordingAuditSink();
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        properties.getReplay().setMode("DISTRIBUTED_HINT");
        TrustSignRequest first = request("hash-1", "anchor-1");
        TrustAuthorityRequestCredentials credentials = credentials("alert-service", "local", "local-dev-trust-hmac-secret", "SIGN", signCredentialPayload(first), "request-distributed-1");

        service(properties, auditSink).sign(credentials, first);

        assertThatThrownBy(() -> service(properties, auditSink).sign(credentials, first))
                .isInstanceOf(TrustAuthorityRequestException.class);
        assertThat(auditSink.events()).extracting(TrustAuthorityAuditEvent::requestId)
                .contains("request-distributed-1");
        assertThat(auditSink.events()).extracting(TrustAuthorityAuditEvent::reasonCode)
                .containsExactly(null, "REPLAY_DETECTED");
    }

    @Test
    void shouldAuthorizeSignWithJwtServiceIdentity() throws Exception {
        KeyPair jwtKey = rsaKeyPair();
        TrustAuthorityService service = service(jwtProperties(jwtKey), new RecordingAuditSink());
        TrustSignRequest request = request("hash-1", "anchor-1");

        TrustSignResponse response = service.sign(jwtCredentials(jwtKey, "alert-service", "trust-authority", List.of("AUDIT_ANCHOR")), request);

        assertThat(response.keyId()).isEqualTo("local-ed25519-key-1");
    }

    @Test
    void shouldAuthorizeSignWithJwtServiceIdentityFromJwksPath() throws Exception {
        KeyPair jwtKey = rsaKeyPair();
        Path jwksPath = tempDir.resolve("jwks.json");
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) jwtKey.getPublic())
                .keyID("jwt-key-1")
                .algorithm(JWSAlgorithm.RS256)
                .build();
        Files.writeString(jwksPath, new JWKSet(jwk).toString());
        TrustAuthorityProperties properties = jwtProperties(jwtKey);
        properties.getJwtIdentity().setKeys(List.of());
        properties.getJwtIdentity().setJwksPath(jwksPath.toString());
        TrustAuthorityService service = service(properties, new RecordingAuditSink());

        TrustSignResponse response = service.sign(jwtCredentials(jwtKey, "alert-service", "trust-authority", List.of("AUDIT_ANCHOR")), request("hash-1", "anchor-1"));

        assertThat(response.keyId()).isEqualTo("local-ed25519-key-1");
    }

    @Test
    void shouldRejectJwtInvalidAudienceExpiredAndUnauthorizedPurpose() throws Exception {
        KeyPair jwtKey = rsaKeyPair();
        TrustAuthorityService service = service(jwtProperties(jwtKey), new RecordingAuditSink());
        TrustSignRequest request = request("hash-1", "anchor-1");

        assertThatThrownBy(() -> service.sign(jwtCredentials(jwtKey, "alert-service", "wrong-audience", List.of("AUDIT_ANCHOR")), request))
                .isInstanceOf(TrustAuthorityRequestException.class);
        assertThatThrownBy(() -> service.sign(jwtCredentials(jwtKey, "alert-service", "trust-authority", List.of("AUDIT_ANCHOR"), Instant.now(), Instant.now().plusSeconds(120), "jwt-key-1", "wrong-issuer"), request))
                .isInstanceOf(TrustAuthorityRequestException.class);
        assertThatThrownBy(() -> service.sign(jwtCredentials(jwtKey, "alert-service", "trust-authority", List.of("AUDIT_ANCHOR"), Instant.now().minusSeconds(700), Instant.now().minusSeconds(100), "jwt-key-1"), request))
                .isInstanceOf(TrustAuthorityRequestException.class);
        assertThatThrownBy(() -> service.sign(jwtCredentials(jwtKey, "fraud-scoring-service", "trust-authority", List.of("AUDIT_ANCHOR")), request))
                .isInstanceOf(TrustAuthorityRequestException.class);
    }

    @Test
    void shouldRejectJwtServiceKeyMismatch() throws Exception {
        KeyPair jwtKey = rsaKeyPair();
        TrustAuthorityProperties properties = jwtProperties(jwtKey);
        properties.getCallers().getFirst().setAllowedJwtKeyIds(List.of("different-key"));
        TrustAuthorityService service = service(properties, new RecordingAuditSink());

        assertThatThrownBy(() -> service.sign(jwtCredentials(jwtKey, "alert-service", "trust-authority", List.of("AUDIT_ANCHOR")), request("hash-1", "anchor-1")))
                .isInstanceOf(TrustAuthorityRequestException.class);
    }

    @Test
    void shouldEnforceJwtKeyRotationWithoutFallbackToAnyKnownKey() throws Exception {
        KeyPair oldKey = rsaKeyPair();
        KeyPair newKey = rsaKeyPair();
        TrustSignRequest request = request("hash-1", "anchor-1");

        TrustAuthorityService oldRemoved = service(jwtProperties(
                List.of(jwtKey("new-key", newKey)),
                caller("alert-service", "", List.of("AUDIT_ANCHOR"), List.of("new-key"))
        ));
        assertThatThrownBy(() -> oldRemoved.sign(jwtCredentials(oldKey, "alert-service", "trust-authority", List.of("AUDIT_ANCHOR"), Instant.now(), Instant.now().plusSeconds(120), "old-key"), request))
                .isInstanceOf(TrustAuthorityRequestException.class);

        TrustAuthorityService newAdded = service(jwtProperties(
                List.of(jwtKey("new-key", newKey)),
                caller("alert-service", "", List.of("AUDIT_ANCHOR"), List.of("new-key"))
        ));
        assertThat(newAdded.sign(jwtCredentials(newKey, "alert-service", "trust-authority", List.of("AUDIT_ANCHOR"), Instant.now(), Instant.now().plusSeconds(120), "new-key"), request).keyId())
                .isEqualTo("local-ed25519-key-1");

        TrustAuthorityService overlap = service(jwtProperties(
                List.of(jwtKey("old-key", oldKey), jwtKey("new-key", newKey)),
                caller("alert-service", "", List.of("AUDIT_ANCHOR"), List.of("old-key", "new-key"))
        ));
        assertThat(overlap.sign(jwtCredentials(oldKey, "alert-service", "trust-authority", List.of("AUDIT_ANCHOR"), Instant.now(), Instant.now().plusSeconds(120), "old-key"), request).keyId())
                .isEqualTo("local-ed25519-key-1");
        assertThat(overlap.sign(jwtCredentials(newKey, "alert-service", "trust-authority", List.of("AUDIT_ANCHOR"), Instant.now(), Instant.now().plusSeconds(120), "new-key"), request("hash-2", "anchor-2")).keyId())
                .isEqualTo("local-ed25519-key-1");
    }

    @Test
    void shouldRejectJwtMisuseAcrossServiceKeyAndAuthorityBoundaries() throws Exception {
        KeyPair alertKey = rsaKeyPair();
        KeyPair scoringKey = rsaKeyPair();
        TrustAuthorityProperties properties = jwtProperties(
                List.of(jwtKey("alert-key", alertKey), jwtKey("scoring-key", scoringKey)),
                caller("alert-service", "", List.of("AUDIT_ANCHOR"), List.of("alert-key")),
                caller("fraud-scoring-service", "", List.of("AUDIT_INTEGRITY"), List.of("scoring-key"))
        );
        TrustAuthorityService service = service(properties, new RecordingAuditSink());
        TrustSignRequest request = request("hash-1", "anchor-1");

        assertThatThrownBy(() -> service.sign(jwtCredentials(scoringKey, "alert-service", "trust-authority", List.of("AUDIT_ANCHOR"), Instant.now(), Instant.now().plusSeconds(120), "scoring-key"), request))
                .isInstanceOf(TrustAuthorityRequestException.class);
        assertThatThrownBy(() -> service.sign(jwtCredentials(alertKey, "fraud-scoring-service", "trust-authority", List.of("AUDIT_ANCHOR"), Instant.now(), Instant.now().plusSeconds(120), "alert-key"), request))
                .isInstanceOf(TrustAuthorityRequestException.class);
        assertThatThrownBy(() -> service.sign(jwtCredentials(alertKey, "alert-service", "trust-authority", List.of(), Instant.now(), Instant.now().plusSeconds(120), "alert-key"), request))
                .isInstanceOf(TrustAuthorityRequestException.class);
    }

    @Test
    void shouldRecordBoundedJwtMisuseMetrics() throws Exception {
        KeyPair jwtKey = rsaKeyPair();
        KeyPair wrongSigningKey = rsaKeyPair();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TrustAuthorityService service = service(jwtProperties(jwtKey), new RecordingAuditSink(), new TrustAuthorityMetrics(registry));
        TrustSignRequest request = request("hash-1", "anchor-1");

        assertThatThrownBy(() -> service.sign(jwtCredentials(wrongSigningKey, "alert-service", "trust-authority", List.of("AUDIT_ANCHOR"), Instant.now(), Instant.now().plusSeconds(120), "jwt-key-1"), request))
                .isInstanceOf(TrustAuthorityRequestException.class);
        assertThatThrownBy(() -> service.sign(jwtCredentials(jwtKey, "alert-service", "trust-authority", List.of("AUDIT_ANCHOR"), Instant.now().minusSeconds(700), Instant.now().minusSeconds(100), "jwt-key-1"), request))
                .isInstanceOf(TrustAuthorityRequestException.class);
        assertThatThrownBy(() -> service.sign(jwtCredentials(jwtKey, "unknown-service", "trust-authority", List.of("AUDIT_ANCHOR")), request))
                .isInstanceOf(TrustAuthorityRequestException.class);
        assertThatThrownBy(() -> service.sign(jwtCredentials(jwtKey, "alert-service", "trust-authority", List.of()), request))
                .isInstanceOf(TrustAuthorityRequestException.class);
        assertThatThrownBy(() -> service.sign(jwtCredentials(jwtKey, "alert-service", "trust-authority", List.of("AUDIT_ANCHOR"), Instant.now(), Instant.now().plusSeconds(120), "jwt-key-1", "wrong-issuer"), request))
                .isInstanceOf(TrustAuthorityRequestException.class);
        TrustAuthorityProperties serviceMismatchProperties = jwtProperties(jwtKey);
        serviceMismatchProperties.getCallers().getFirst().setAllowedJwtKeyIds(List.of("other-key"));
        TrustAuthorityService serviceMismatchService = service(serviceMismatchProperties, new RecordingAuditSink(), new TrustAuthorityMetrics(registry));
        assertThatThrownBy(() -> serviceMismatchService.sign(jwtCredentials(jwtKey, "alert-service", "trust-authority", List.of("AUDIT_ANCHOR")), request))
                .isInstanceOf(TrustAuthorityRequestException.class);

        assertThat(registry.get("trust_jwt_invalid_total").tag("reason", "invalid_signature").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("trust_jwt_invalid_total").tag("reason", "expired").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("trust_jwt_invalid_total").tag("reason", "unauthorized_service").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("trust_jwt_invalid_total").tag("reason", "authority_missing").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("trust_jwt_invalid_total").tag("reason", "invalid_claim").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("trust_jwt_invalid_total").tag("reason", "kid_mismatch").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("trust_jwt_expired_total").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("trust_jwt_authority_missing_total").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("trust_jwt_kid_mismatch_total").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("trust_jwt_service_mismatch_total").counter().count()).isEqualTo(1.0d);
    }

    @Test
    void shouldRejectAdversarialJwtClaims() throws Exception {
        KeyPair jwtKey = rsaKeyPair();
        TrustAuthorityService service = service(jwtProperties(jwtKey), new RecordingAuditSink());
        TrustSignRequest request = request("hash-1", "anchor-1");

        assertThatThrownBy(() -> service.sign(jwtCredentials(jwtKey, "alert-service", "trust-authority", List.of("AUDIT_ANCHOR"), Instant.now(), Instant.now().plusSeconds(120), "jwt-key-1", "wrong-issuer"), request))
                .isInstanceOf(TrustAuthorityRequestException.class);
        assertThatThrownBy(() -> service.sign(jwtCredentials(jwtKey, "alert-service", "wrong-audience", List.of("AUDIT_ANCHOR")), request))
                .isInstanceOf(TrustAuthorityRequestException.class);
        assertThatThrownBy(() -> service.sign(jwtCredentials(jwtKey, "alert-service", "trust-authority", List.of("AUDIT_ANCHOR"), Instant.now().minusSeconds(700), Instant.now().minusSeconds(100), "jwt-key-1"), request))
                .isInstanceOf(TrustAuthorityRequestException.class);
        assertThatThrownBy(() -> service.sign(jwtCredentials(jwtKey, "alert-service", "trust-authority", List.of("AUDIT_ANCHOR"), Instant.now().plusSeconds(120), Instant.now().plusSeconds(180), "jwt-key-1"), request))
                .isInstanceOf(TrustAuthorityRequestException.class);
        assertThatThrownBy(() -> service.sign(jwtCredentials(jwtKey, "alert-service", "trust-authority", List.of("AUDIT_ANCHOR"), Instant.now(), Instant.now().plusSeconds(600), "jwt-key-1"), request))
                .isInstanceOf(TrustAuthorityRequestException.class);
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

    @Test
    void shouldExposeAuditHeadForExternalAnchoring() {
        RecordingAuditSink auditSink = new RecordingAuditSink();
        TrustAuthorityService service = service(new TrustAuthorityProperties(), auditSink);
        TrustSignRequest request = request("hash-1", "anchor-1");

        sign(service, request);
        TrustAuthorityAuditHeadResponse head = service.auditHead(credentials(
                "alert-service",
                "local",
                "local-dev-trust-hmac-secret",
                "AUDIT_INTEGRITY",
                "trust-authority-audit-head"
        ));

        assertThat(head.chainPosition()).isEqualTo(1L);
        assertThat(head.eventHash()).isNotBlank();
        assertThat(head.status()).isEqualTo("AVAILABLE");
        assertThat(head.source()).isEqualTo("trust-authority-audit");
        assertThat(head.proofType()).isEqualTo("LOCAL_HASH_CHAIN_HEAD");
        assertThat(head.integrityHint()).isEqualTo("LOCAL_CHAIN_ONLY");
        assertThat(head.capabilityLevel()).isEqualTo(TrustAuthorityCapabilityLevel.INTERNAL_CRYPTOGRAPHIC_TRUST);
        assertThat(head.occurredAt()).isNotNull();
        assertThat(auditSink.events()).extracting(TrustAuthorityAuditEvent::payloadHash)
                .contains("trust-authority-audit-head");
    }

    @Test
    void shouldExposeExplicitEmptyAuditHead() {
        TrustAuthorityAuditHeadResponse head = TrustAuthorityAuditHeadResponse.empty();

        assertThat(head.status()).isEqualTo("EMPTY");
        assertThat(head.chainPosition()).isNull();
        assertThat(head.eventHash()).isNull();
        assertThat(head.occurredAt()).isNull();
        assertThat(head.integrityHint()).isEqualTo("LOCAL_CHAIN_ONLY");
    }

    @Test
    void shouldRecordAuditIntegrityAnomalyMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger calls = new AtomicInteger();
        TrustAuthorityAuditSink sink = new TrustAuthorityAuditSink() {
            @Override
            public void append(TrustAuthorityAuditEvent event) {
            }

            @Override
            public TrustAuthorityAuditIntegrityResponse integrity(int limit) {
                return integrity(limit, "WINDOW");
            }

            @Override
            public TrustAuthorityAuditIntegrityResponse integrity(int limit, String mode) {
                if (calls.getAndIncrement() == 0) {
                    return new TrustAuthorityAuditIntegrityResponse(
                            "PARTIAL",
                            1,
                            "WINDOW",
                            TrustAuthorityCapabilityLevel.INTERNAL_CRYPTOGRAPHIC_TRUST,
                            false,
                            TrustAuthorityIntegrityConfidence.PARTIAL_BOUNDARY,
                            2L,
                            "hash-2",
                            2L,
                            2L,
                            "hash-1",
                            "BOUNDARY_PREDECESSOR_OUTSIDE_WINDOW",
                            List.of(),
                            null
                    );
                }
                return new TrustAuthorityAuditIntegrityResponse(
                        "INVALID",
                        1,
                        "WINDOW",
                        TrustAuthorityCapabilityLevel.INTERNAL_CRYPTOGRAPHIC_TRUST,
                        true,
                        TrustAuthorityIntegrityConfidence.PARTIAL_BOUNDARY,
                        2L,
                        "hash-2",
                        2L,
                        2L,
                        "hash-1",
                        "AUDIT_CHAIN_INVALID",
                        List.of(),
                        null
                );
            }

            @Override
            public TrustAuthorityAuditHeadResponse head() {
                return TrustAuthorityAuditHeadResponse.empty();
            }
        };
        TrustAuthorityService service = service(new TrustAuthorityProperties(), sink, new TrustAuthorityMetrics(registry));

        TrustAuthorityAuditIntegrityResponse partial = service.auditIntegrity(credentials("alert-service", "local", "local-dev-trust-hmac-secret", "AUDIT_INTEGRITY", "trust-authority-audit-integrity"), 100, "WINDOW");
        service.auditIntegrity(credentials("alert-service", "local", "local-dev-trust-hmac-secret", "AUDIT_INTEGRITY", "trust-authority-audit-integrity"), 100, "WINDOW");

        assertThat(partial.trustDecisionTrace()).isNotNull();
        assertThat(partial.trustDecisionTrace().identityVerified()).isTrue();
        assertThat(partial.trustDecisionTrace().chainVerified()).isEqualTo("PARTIAL");
        assertThat(partial.trustDecisionTrace().finalStatus()).isEqualTo("PARTIAL");
        assertThat(registry.get("trust_audit_integrity_partial_total").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("trust_audit_integrity_invalid_total").counter().count()).isEqualTo(1.0d);
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

    private TrustAuthorityService service(TrustAuthorityProperties properties, TrustAuthorityAuditSink auditSink, TrustAuthorityMetrics metrics) {
        return new TrustAuthorityService(
                properties,
                auditSink,
                new TrustAuthorityRateLimiter(),
                new TrustAuthorityRequestReplayGuard(),
                metrics,
                environment("test")
        );
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

    private TrustAuthorityProperties.CallerEntry caller(String serviceName, String token, List<String> purposes, List<String> jwtKeyIds) {
        TrustAuthorityProperties.CallerEntry caller = caller(serviceName, token, purposes);
        caller.setAllowedJwtKeyIds(jwtKeyIds);
        return caller;
    }

    private KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
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
                payload)), null);
    }

    private TrustAuthorityRequestCredentials jwtCredentials(KeyPair keyPair, String serviceName, String audience, List<String> authorities) {
        return jwtCredentials(keyPair, serviceName, audience, authorities, Instant.now(), Instant.now().plusSeconds(120), "jwt-key-1");
    }

    private TrustAuthorityRequestCredentials jwtCredentials(
            KeyPair keyPair,
            String serviceName,
            String audience,
            List<String> authorities,
            Instant issuedAt,
            Instant expiresAt,
            String keyId
    ) {
        return jwtCredentials(keyPair, serviceName, audience, authorities, issuedAt, expiresAt, keyId, "issuer-1");
    }

    private TrustAuthorityRequestCredentials jwtCredentials(
            KeyPair keyPair,
            String serviceName,
            String audience,
            List<String> authorities,
            Instant issuedAt,
            Instant expiresAt,
            String keyId,
            String issuer
    ) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(audience)
                    .subject(serviceName)
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(expiresAt))
                    .claim("service_name", serviceName)
                    .claim("authorities", authorities)
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .type(JOSEObjectType.JWT)
                            .keyID(keyId)
                            .build(),
                    claims
            );
            jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
            return new TrustAuthorityRequestCredentials(
                    TrustAuthorityCallerIdentity.of(null, null, null),
                    java.util.UUID.randomUUID().toString(),
                    null,
                    null,
                    jwt.serialize()
            );
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private TrustAuthorityProperties jwtProperties(KeyPair jwtKey) {
        return jwtProperties(
                List.of(jwtKey("jwt-key-1", jwtKey)),
                caller("alert-service", "", List.of("AUDIT_ANCHOR", "AUDIT_INTEGRITY"), List.of("jwt-key-1"))
        );
    }

    private TrustAuthorityProperties jwtProperties(List<TrustAuthorityProperties.JwtKeyEntry> keys, TrustAuthorityProperties.CallerEntry... callers) {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        properties.setIdentityMode("jwt-service-identity");
        properties.getJwtIdentity().setIssuer("issuer-1");
        properties.getJwtIdentity().setAudience("trust-authority");
        properties.getJwtIdentity().setKeys(keys);
        properties.setCallers(List.of(callers));
        return properties;
    }

    private TrustAuthorityProperties.JwtKeyEntry jwtKey(String keyId, KeyPair jwtKey) {
        TrustAuthorityProperties.JwtKeyEntry key = new TrustAuthorityProperties.JwtKeyEntry();
        key.setKeyId(keyId);
        key.setPublicKey(Base64.getEncoder().encodeToString(jwtKey.getPublic().getEncoded()));
        return key;
    }

    private void configurePersistentSigningKeys(TrustAuthorityProperties properties) throws Exception {
        KeyPair signingKey = keyPair();
        Path privateKey = tempDir.resolve("ed25519-private.key");
        Path publicKey = tempDir.resolve("ed25519-public.key");
        Files.writeString(privateKey, Base64.getEncoder().encodeToString(signingKey.getPrivate().getEncoded()));
        restrictPrivateKeyPermissions(privateKey);
        Files.writeString(publicKey, Base64.getEncoder().encodeToString(signingKey.getPublic().getEncoded()));
        properties.setPrivateKeyPath(privateKey.toString());
        properties.setPublicKeyPath(publicKey.toString());
    }

    private void restrictPrivateKeyPermissions(Path privateKey) throws Exception {
        try {
            Files.setPosixFilePermissions(privateKey, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            ));
        } catch (UnsupportedOperationException ignored) {
            // Windows test filesystems do not expose POSIX permissions.
        }
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

    private String fingerprint(byte[] publicKey) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static class RecordingAuditSink implements TrustAuthorityAuditSink {
        private final List<TrustAuthorityAuditEvent> events = new ArrayList<>();

        @Override
        public void append(TrustAuthorityAuditEvent event) {
            TrustAuthorityAuditEvent latest = events.isEmpty() ? null : events.getLast();
            long nextPosition = latest == null ? 1L : latest.chainPosition() + 1L;
            String previousHash = latest == null ? null : latest.eventHash();
            events.add(event.withChain(
                    previousHash,
                    TrustAuthorityAuditHasher.hash(event, previousHash, nextPosition),
                    nextPosition
            ));
        }

        @Override
        public TrustAuthorityAuditIntegrityResponse integrity(int limit) {
            return TrustAuthorityAuditIntegrityVerifier.verify(events);
        }

        @Override
        public TrustAuthorityAuditHeadResponse head() {
            if (events.isEmpty()) {
                return TrustAuthorityAuditHeadResponse.empty();
            }
            return TrustAuthorityAuditHeadResponse.from(events.getLast());
        }

        @Override
        public boolean requestSeen(String callerService, String requestId) {
            return events.stream()
                    .anyMatch(event -> callerService.equals(event.callerService()) && requestId.equals(event.requestId()));
        }

        List<TrustAuthorityAuditEvent> events() {
            return events;
        }
    }
}
