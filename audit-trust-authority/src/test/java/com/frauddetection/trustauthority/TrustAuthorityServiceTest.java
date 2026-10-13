package com.frauddetection.trustauthority;

import com.frauddetection.trustauthority.api.TrustKeyResponse;
import com.frauddetection.trustauthority.api.TrustSignRequest;
import com.frauddetection.trustauthority.api.TrustSignResponse;
import com.frauddetection.trustauthority.api.TrustVerifyRequest;
import com.frauddetection.trustauthority.api.TrustVerifyResponse;

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
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
    void shouldRejectAnySignedTimestampModificationInsideTheSameKeyValidityWindow() throws Exception {
        KeyPair keyPair = keyPair();
        Instant signingTime = Instant.now();
        TrustAuthorityProperties.KeyEntry key = key("key-timestamp", "ACTIVE", keyPair);
        key.setValidFrom(signingTime.minusSeconds(86_400));
        key.setValidUntil(signingTime.plusSeconds(86_400));
        TrustAuthorityService service = service(
                propertiesWithKeys(key),
                new RecordingAuditSink(),
                new TrustAuthorityMetrics(new SimpleMeterRegistry()),
                Clock.fixed(signingTime, java.time.ZoneOffset.UTC)
        );
        TrustSignRequest request = request("hash-timestamp", "anchor-timestamp");
        TrustSignResponse signature = sign(service, request);

        assertThat(signature.signedAt())
                .isEqualTo(signingTime.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        assertThat(verifyWithTimestamp(service, request, signature, signature.signedAt().plusSeconds(1)).status())
                .isEqualTo("INVALID");
        assertThat(verifyWithTimestamp(service, request, signature, signature.signedAt().plusNanos(1)).status())
                .isEqualTo("INVALID");
    }

    @Test
    void shouldRejectMissingSignedTimestamp() {
        TrustAuthorityService service = service("key-1");
        TrustSignRequest request = request("hash-1", "anchor-1");
        TrustSignResponse signature = sign(service, request);

        TrustVerifyResponse verified = verifyWithTimestamp(service, request, signature, null);

        assertThat(verified.status()).isEqualTo("INVALID");
        assertThat(verified.reasonCode()).isEqualTo("SIGNED_AT_MISSING");
    }

    @Test
    void shouldVerifyAfterCanonicalTimestampTextRoundTrip() {
        TrustAuthorityService service = service("key-1");
        TrustSignRequest request = request("hash-round-trip", "anchor-round-trip");
        TrustSignResponse signature = sign(service, request);
        Instant deserializedSignedAt = Instant.parse(signature.signedAt().toString());

        assertThat(verifyWithTimestamp(service, request, signature, deserializedSignedAt).status())
                .isEqualTo("VALID");
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
    void shouldVerifyRetiredInlinePublicOnlyKeyAfterRotation() throws Exception {
        KeyPair retiredPair = keyPair();
        TrustSignRequest request = request("hash-retired-inline", "anchor-retired-inline");
        TrustSignResponse historicalSignature = sign(
                service(propertiesWithKeys(key("retired-inline", "ACTIVE", retiredPair))),
                request
        );
        TrustAuthorityService rotatedService = service(propertiesWithKeys(
                publicOnlyKey("retired-inline", "RETIRED", retiredPair),
                key("active-inline", "ACTIVE", keyPair())
        ));

        TrustVerifyResponse verified = verify(rotatedService, new TrustVerifyRequest(
                request.purpose(), request.payloadHash(), request.partitionKey(), request.chainPosition(),
                request.anchorId(), historicalSignature.signature(), historicalSignature.keyId(),
                historicalSignature.signedAt()
        ));
        TrustSignResponse newSignature = sign(rotatedService, request);

        assertThat(verified.status()).isEqualTo("VALID");
        assertThat(newSignature.keyId()).isEqualTo("active-inline");
    }

    @Test
    void shouldVerifyRetiredPersistedPublicKeyWhenConfiguredPrivateFileIsAbsent() throws Exception {
        KeyPair retiredPair = keyPair();
        TrustSignRequest request = request("hash-retired-file", "anchor-retired-file");
        TrustSignResponse historicalSignature = sign(
                service(propertiesWithKeys(key("retired-file", "ACTIVE", retiredPair))),
                request
        );
        Path publicKey = tempDir.resolve("retired/public.key");
        Path absentPrivateKey = tempDir.resolve("retired/private.key");
        Files.createDirectories(publicKey.getParent());
        Files.writeString(publicKey, Base64.getEncoder().encodeToString(retiredPair.getPublic().getEncoded()));
        TrustAuthorityService rotatedService = service(propertiesWithKeys(
                persistedKey("retired-file", "RETIRED", absentPrivateKey, publicKey),
                key("active-file-rotation", "ACTIVE", keyPair())
        ));

        TrustVerifyResponse verified = verify(rotatedService, new TrustVerifyRequest(
                request.purpose(), request.payloadHash(), request.partitionKey(), request.chainPosition(),
                request.anchorId(), historicalSignature.signature(), historicalSignature.keyId(),
                historicalSignature.signedAt()
        ));

        assertThat(verified.status()).isEqualTo("VALID");
        assertThat(absentPrivateKey).doesNotExist();
    }

    @Test
    void shouldFailClosedForActivePublicOnlyAndMalformedRetiredPublicMaterial() throws Exception {
        KeyPair pair = keyPair();
        TrustAuthorityProperties.KeyEntry malformedRetired = keyWithoutMaterial("malformed-retired", "RETIRED");
        malformedRetired.setPublicKey("not-an-ed25519-public-key");

        assertThatThrownBy(() -> new TrustAuthorityKeyRegistry(
                propertiesWithKeys(publicOnlyKey("active-public-only", "ACTIVE", pair)),
                true
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("complete key pair");
        assertThatThrownBy(() -> new TrustAuthorityKeyRegistry(
                propertiesWithKeys(malformedRetired, key("active-with-malformed-retired", "ACTIVE", keyPair())),
                true
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be initialized");
    }

    @Test
    void shouldRequireExactlyOneActiveSigningKey() throws Exception {
        TrustAuthorityProperties zeroActive = propertiesWithKeys(key("key-v1", "RETIRED", keyPair()));
        TrustAuthorityProperties twoActive = propertiesWithKeys(
                key("key-v1", "ACTIVE", keyPair()),
                key("key-v2", "ACTIVE", keyPair())
        );

        assertThatThrownBy(() -> new TrustAuthorityKeyRegistry(zeroActive, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one active");
        assertThatThrownBy(() -> new TrustAuthorityKeyRegistry(twoActive, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one active");
    }

    @Test
    void shouldRejectDuplicateKeyIdsAndMismatchedActiveKeyPair() throws Exception {
        KeyPair first = keyPair();
        KeyPair second = keyPair();
        TrustAuthorityProperties duplicateIds = propertiesWithKeys(
                key("same-key", "ACTIVE", first),
                key("same-key", "RETIRED", second)
        );
        TrustAuthorityProperties mismatchedPair = propertiesWithKeys(
                key("key-v1", "ACTIVE", new KeyPair(first.getPublic(), second.getPrivate()))
        );

        assertThatThrownBy(() -> new TrustAuthorityKeyRegistry(duplicateIds, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("globally unique");
        assertThatThrownBy(() -> new TrustAuthorityKeyRegistry(mismatchedPair, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("do not match");
    }

    @Test
    void shouldRejectContradictoryKeyValidityWindow() throws Exception {
        TrustAuthorityProperties.KeyEntry entry = key("key-v1", "ACTIVE", keyPair());
        entry.setValidFrom(Instant.parse("2026-05-01T00:00:00Z"));
        entry.setValidUntil(Instant.parse("2026-04-30T23:59:59Z"));

        assertThatThrownBy(() -> new TrustAuthorityKeyRegistry(propertiesWithKeys(entry), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("validity window");
    }

    @Test
    void shouldRejectBlankStatusAndNonCanonicalKeyId() throws Exception {
        TrustAuthorityProperties.KeyEntry blankStatus = key("key-v1", " ", keyPair());
        TrustAuthorityProperties.KeyEntry paddedKeyId = key(" key-v1", "ACTIVE", keyPair());

        assertThatThrownBy(() -> new TrustAuthorityKeyRegistry(propertiesWithKeys(blankStatus), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("status");
        assertThatThrownBy(() -> new TrustAuthorityKeyRegistry(propertiesWithKeys(paddedKeyId), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key ids");
    }

    @Test
    void shouldAllowEphemeralKeyGenerationOnlyForNonProductionLocalProfiles() {
        TrustAuthorityProperties properties = propertiesWithKeys(keyWithoutMaterial("ephemeral-key", "ACTIVE"));

        assertThat(new TrustAuthorityKeyRegistry(properties, environment(" local ")).activeKey().keyId())
                .isEqualTo("ephemeral-key");
        assertThat(new TrustAuthorityKeyRegistry(properties, environment("DEV")).activeKey().keyId())
                .isEqualTo("ephemeral-key");
        assertThat(new TrustAuthorityKeyRegistry(properties, environment("test")).activeKey().keyId())
                .isEqualTo("ephemeral-key");
        assertThat(new TrustAuthorityKeyRegistry(properties, environment("docker-local")).activeKey().keyId())
                .isEqualTo("ephemeral-key");
    }

    @Test
    void shouldRejectEphemeralKeyGenerationWhenAnyProductionProfileIsActive() {
        TrustAuthorityProperties properties = propertiesWithKeys(keyWithoutMaterial("ephemeral-key", "ACTIVE"));

        assertThatThrownBy(() -> new TrustAuthorityKeyRegistry(properties, environment("prod", "local")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key material is required");
        assertThatThrownBy(() -> new TrustAuthorityKeyRegistry(properties, environment(" staging ", " TEST ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key material is required");
        assertThatThrownBy(() -> new TrustAuthorityKeyRegistry(properties, environment("PRODUCTION", "docker-local")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key material is required");
    }

    @Test
    void shouldBootstrapPersistedKeyPairOnceAndPreserveIdentityAcrossRestart() throws Exception {
        Path privateKey = tempDir.resolve("bootstrap/private.key");
        Path publicKey = tempDir.resolve("bootstrap/public.key");
        TrustAuthorityProperties properties = propertiesWithKeys(
                persistedKey("stable-key", "ACTIVE", privateKey, publicKey)
        );

        TrustAuthorityKeyRegistry first = new TrustAuthorityKeyRegistry(properties, environment("local"));
        String privateMaterial = Files.readString(privateKey);
        String publicMaterial = Files.readString(publicKey);
        TrustAuthorityKeyRegistry restarted = new TrustAuthorityKeyRegistry(properties, environment("local"));

        assertThat(restarted.activeResponse().keyId()).isEqualTo("stable-key");
        assertThat(restarted.activeResponse().keyFingerprintSha256())
                .isEqualTo(first.activeResponse().keyFingerprintSha256());
        assertThat(Files.readString(privateKey)).isEqualTo(privateMaterial);
        assertThat(Files.readString(publicKey)).isEqualTo(publicMaterial);
    }

    @Test
    void shouldPreserveWinningIdentityDuringConcurrentPersistedBootstrap() throws Exception {
        Path privateKey = tempDir.resolve("concurrent-bootstrap/private.key");
        Path publicKey = tempDir.resolve("concurrent-bootstrap/public.key");
        TrustAuthorityProperties properties = propertiesWithKeys(
                persistedKey("concurrent-key", "ACTIVE", privateKey, publicKey)
        );
        CountDownLatch start = new CountDownLatch(1);
        Callable<Optional<String>> bootstrap = () -> {
            start.await();
            try {
                return Optional.of(new TrustAuthorityKeyRegistry(properties, environment("local"))
                        .activeResponse()
                        .keyFingerprintSha256());
            } catch (IllegalStateException exception) {
                return Optional.empty();
            }
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(bootstrap);
            var second = executor.submit(bootstrap);
            start.countDown();
            List<String> successfulFingerprints = List.of(first.get(), second.get()).stream()
                    .flatMap(Optional::stream)
                    .toList();
            String persistedFingerprint = new TrustAuthorityKeyRegistry(properties, environment("local"))
                    .activeResponse()
                    .keyFingerprintSha256();

            assertThat(successfulFingerprints).isNotEmpty().containsOnly(persistedFingerprint);
            assertThat(privateKey).isRegularFile();
            assertThat(publicKey).isRegularFile();
        }
    }

    @Test
    void shouldRejectPartialPersistedKeyPairWithoutChangingExistingMaterial() throws Exception {
        Path privateKey = tempDir.resolve("partial/private.key");
        Path publicKey = tempDir.resolve("partial/public.key");
        Files.createDirectories(privateKey.getParent());
        Files.writeString(privateKey, "existing-private-material");
        TrustAuthorityProperties properties = propertiesWithKeys(
                persistedKey("partial-key", "ACTIVE", privateKey, publicKey)
        );

        assertThatThrownBy(() -> new TrustAuthorityKeyRegistry(properties, environment("local")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("partial");
        assertThat(Files.readString(privateKey)).isEqualTo("existing-private-material");
        assertThat(publicKey).doesNotExist();
    }

    @Test
    void shouldRejectPublicOnlyPersistedKeyPairWithoutChangingExistingMaterial() throws Exception {
        Path privateKey = tempDir.resolve("public-only/private.key");
        Path publicKey = tempDir.resolve("public-only/public.key");
        Files.createDirectories(publicKey.getParent());
        Files.writeString(publicKey, "existing-public-material");
        TrustAuthorityProperties properties = propertiesWithKeys(
                persistedKey("public-only-key", "ACTIVE", privateKey, publicKey)
        );

        assertThatThrownBy(() -> new TrustAuthorityKeyRegistry(properties, environment("local")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("partial");
        assertThat(Files.readString(publicKey)).isEqualTo("existing-public-material");
        assertThat(privateKey).doesNotExist();
    }

    @Test
    void shouldRejectSharedPersistedKeyPathBeforeCreatingMaterial() {
        Path sharedPath = tempDir.resolve("shared/key.material");
        TrustAuthorityProperties properties = propertiesWithKeys(
                persistedKey("shared-path-key", "ACTIVE", sharedPath, sharedPath)
        );

        assertThatThrownBy(() -> new TrustAuthorityKeyRegistry(properties, environment("local")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paths must be distinct");
        assertThat(sharedPath).doesNotExist();
    }

    @Test
    void shouldRejectConflictingInlineAndPersistedKeyConfiguration() throws Exception {
        TrustAuthorityProperties.KeyEntry entry = key("conflicting-key", "ACTIVE", keyPair());
        entry.setPrivateKeyPath(tempDir.resolve("conflict/private.key").toString());
        entry.setPublicKeyPath(tempDir.resolve("conflict/public.key").toString());

        assertThatThrownBy(() -> new TrustAuthorityKeyRegistry(propertiesWithKeys(entry), environment("local")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot mix inline and persisted");
    }

    @Test
    void shouldRejectMalformedOrMismatchedPersistedKeyMaterial() throws Exception {
        Path malformedPrivate = tempDir.resolve("malformed/private.key");
        Path malformedPublic = tempDir.resolve("malformed/public.key");
        Files.createDirectories(malformedPrivate.getParent());
        Files.writeString(malformedPrivate, "not-a-private-key");
        Files.writeString(malformedPublic, "not-a-public-key");

        assertThatThrownBy(() -> new TrustAuthorityKeyRegistry(propertiesWithKeys(
                persistedKey("malformed-key", "ACTIVE", malformedPrivate, malformedPublic)
        ), environment("local")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be initialized");

        KeyPair first = keyPair();
        KeyPair second = keyPair();
        Path mismatchPrivate = tempDir.resolve("mismatch/private.key");
        Path mismatchPublic = tempDir.resolve("mismatch/public.key");
        Files.createDirectories(mismatchPrivate.getParent());
        Files.writeString(mismatchPrivate, Base64.getEncoder().encodeToString(first.getPrivate().getEncoded()));
        Files.writeString(mismatchPublic, Base64.getEncoder().encodeToString(second.getPublic().getEncoded()));

        assertThatThrownBy(() -> new TrustAuthorityKeyRegistry(propertiesWithKeys(
                persistedKey("mismatched-key", "ACTIVE", mismatchPrivate, mismatchPublic)
        ), environment("local")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("do not match");
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
                .hasMessage("Demo local secret detected outside local/dev/docker-local profile or explicit test-fixture context.");
    }

    @Test
    void shouldRejectDefaultDemoTokenOutsideLocalProfiles() {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();

        assertThatThrownBy(() -> new TrustAuthorityRuntimeGuard(properties, environment("qa")).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Demo local secret detected outside local/dev/docker-local profile or explicit test-fixture context.");
    }

    @Test
    void shouldAllowDefaultDemoTokenInDockerLocalProfile() {
        new TrustAuthorityRuntimeGuard(new TrustAuthorityProperties(), environment("docker-local")).run(null);
    }

    @Test
    void shouldNotTreatMixedProductionAndLocalProfilesAsLocalFixtureContext() {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        properties.getAudit().setSink("durable-hash-chain");

        assertThatThrownBy(() -> new TrustAuthorityRuntimeGuard(properties, environment(" PROD ", "local")).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Demo local secret detected outside local/dev/docker-local profile or explicit test-fixture context.");
    }

    @Test
    void shouldRejectDefaultDemoTokenInGenericDockerProfile() {
        assertThatThrownBy(() -> new TrustAuthorityRuntimeGuard(new TrustAuthorityProperties(), environment("docker")).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Demo local secret detected outside local/dev/docker-local profile or explicit test-fixture context.");
    }

    @Test
    void shouldRejectDefaultDemoTokenInGenericTestProfile() {
        MockEnvironment environment = environment("test");
        environment.setProperty("CI", "false");

        assertThatThrownBy(() -> new TrustAuthorityRuntimeGuard(new TrustAuthorityProperties(), environment).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Demo local secret detected outside local/dev/docker-local profile or explicit test-fixture context.");
    }

    @Test
    void shouldAllowDefaultDemoTokenInExplicitTestFixtureContext() {
        MockEnvironment environment = environment("test");
        environment.setProperty("LOCAL_FIXTURE_TEST_ENABLED", "true");

        new TrustAuthorityRuntimeGuard(new TrustAuthorityProperties(), environment).run(null);
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
    void shouldAllowPersistedActiveAndPublicOnlyRetiredAndRevokedKeysInProdLikeProfile() throws Exception {
        KeyPair activePair = keyPair();
        KeyPair retiredPair = keyPair();
        KeyPair revokedPair = keyPair();
        Path activePrivate = tempDir.resolve("lifecycle/active-private.key");
        Path activePublic = tempDir.resolve("lifecycle/active-public.key");
        Path retiredPublic = tempDir.resolve("lifecycle/retired-public.key");
        Path revokedPublic = tempDir.resolve("lifecycle/revoked-public.key");
        Files.createDirectories(activePrivate.getParent());
        Files.writeString(activePrivate, Base64.getEncoder().encodeToString(activePair.getPrivate().getEncoded()));
        restrictPrivateKeyPermissions(activePrivate);
        Files.writeString(activePublic, Base64.getEncoder().encodeToString(activePair.getPublic().getEncoded()));
        Files.writeString(retiredPublic, Base64.getEncoder().encodeToString(retiredPair.getPublic().getEncoded()));
        Files.writeString(revokedPublic, Base64.getEncoder().encodeToString(revokedPair.getPublic().getEncoded()));

        TrustAuthorityProperties.KeyEntry retired = keyWithoutMaterial("retired-public", "RETIRED");
        retired.setPublicKeyPath(retiredPublic.toString());
        TrustAuthorityProperties.KeyEntry revoked = keyWithoutMaterial("revoked-public", "REVOKED");
        revoked.setPublicKeyPath(revokedPublic.toString());
        TrustAuthorityProperties properties = jwtProperties(rsaKeyPair());
        properties.getAudit().setSink("durable-hash-chain");
        properties.setKeys(List.of(
                persistedKey("active-pair", "ACTIVE", activePrivate, activePublic),
                retired,
                revoked
        ));

        new TrustAuthorityRuntimeGuard(properties, environment("prod")).run(null);
        TrustAuthorityKeyRegistry registry = new TrustAuthorityKeyRegistry(properties, environment("prod"));

        assertThat(registry.responses()).extracting(TrustKeyResponse::status)
                .containsExactly("ACTIVE", "RETIRED", "REVOKED");
        assertThat(registry.responses()).extracting(TrustKeyResponse::keyFingerprintSha256)
                .allSatisfy(fingerprint -> assertThat(fingerprint).isNotBlank());
    }

    @Test
    void shouldRequireRevokedPublicKeyPathInMixedProdLikeProfile() throws Exception {
        TrustAuthorityProperties properties = jwtProperties(rsaKeyPair());
        properties.getAudit().setSink("durable-hash-chain");
        configurePersistentSigningKeys(properties);
        TrustAuthorityProperties.KeyEntry revoked = keyWithoutMaterial("revoked-without-path", "REVOKED");
        properties.setKeys(List.of(properties.getKeys().getFirst(), revoked));

        assertThatThrownBy(() -> new TrustAuthorityRuntimeGuard(
                properties,
                environment("prod", "local")
        ).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("persistent public key path");
    }

    @Test
    void shouldRejectMalformedRevokedPublicMaterialInLocalProfile() throws Exception {
        TrustAuthorityProperties.KeyEntry malformedRevoked = keyWithoutMaterial("malformed-revoked", "REVOKED");
        malformedRevoked.setPublicKey("not-an-ed25519-public-key");

        assertThatThrownBy(() -> new TrustAuthorityKeyRegistry(
                propertiesWithKeys(malformedRevoked, key("active-with-revoked", "ACTIVE", keyPair())),
                environment("local")
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be initialized");
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
        Instant signingTime = Instant.now();
        TrustSignRequest request = request("hash-1", "anchor-1");
        TrustAuthorityProperties.KeyEntry verificationKey = key("key-v1", "ACTIVE", keyPair);
        verificationKey.setValidFrom(signingTime.minusSeconds(86_400));
        verificationKey.setValidUntil(signingTime.plusSeconds(86_400));
        TrustAuthorityService service = service(
                propertiesWithKeys(verificationKey),
                new RecordingAuditSink(),
                new TrustAuthorityMetrics(new SimpleMeterRegistry()),
                Clock.fixed(signingTime, java.time.ZoneOffset.UTC)
        );
        TrustSignResponse signature = sign(service, request);

        TrustVerifyResponse expired = verify(service, new TrustVerifyRequest(
                request.purpose(), request.payloadHash(), request.partitionKey(), request.chainPosition(),
                request.anchorId(), signature.signature(), signature.keyId(), signingTime.plusSeconds(172_800)
        ));
        TrustVerifyResponse valid = verify(service, new TrustVerifyRequest(
                request.purpose(), request.payloadHash(), request.partitionKey(), request.chainPosition(),
                request.anchorId(), signature.signature(), signature.keyId(), signature.signedAt()
        ));

        assertThat(expired.status()).isEqualTo("INVALID");
        assertThat(expired.reasonCode()).isEqualTo("KEY_EXPIRED");
        assertThat(valid.status()).isEqualTo("VALID");
    }

    @Test
    void shouldRejectSigningOutsideActiveKeyWindowWithoutSuccessAudit() throws Exception {
        Instant now = Instant.now();
        TrustAuthorityProperties.KeyEntry notYetValid = key("key-v1", "ACTIVE", keyPair());
        notYetValid.setValidFrom(now.plusSeconds(60));
        RecordingAuditSink notYetValidAudit = new RecordingAuditSink();
        TrustAuthorityService earlyService = service(
                propertiesWithKeys(notYetValid),
                notYetValidAudit,
                new TrustAuthorityMetrics(new SimpleMeterRegistry()),
                Clock.fixed(now, java.time.ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> sign(earlyService, request("hash-early", "anchor-early")))
                .isInstanceOf(TrustAuthorityRequestException.class);
        assertThat(notYetValidAudit.events()).noneMatch(event -> "SUCCESS".equals(event.result()));
        assertThat(notYetValidAudit.events()).anyMatch(event -> "KEY_NOT_YET_VALID".equals(event.reasonCode()));

        TrustAuthorityProperties.KeyEntry expired = key("key-v2", "ACTIVE", keyPair());
        expired.setValidUntil(now.minusSeconds(1));
        RecordingAuditSink expiredAudit = new RecordingAuditSink();
        TrustAuthorityService expiredService = service(
                propertiesWithKeys(expired),
                expiredAudit,
                new TrustAuthorityMetrics(new SimpleMeterRegistry()),
                Clock.fixed(now, java.time.ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> sign(expiredService, request("hash-expired", "anchor-expired")))
                .isInstanceOf(TrustAuthorityRequestException.class);
        assertThat(expiredAudit.events()).noneMatch(event -> "SUCCESS".equals(event.result()));
        assertThat(expiredAudit.events()).anyMatch(event -> "KEY_EXPIRED".equals(event.reasonCode()));
    }

    @Test
    void shouldRejectDisabledSigningRequiredInProdLikeProfile() {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        properties.setHmacSecret("prod-secret");
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
        properties.setKeys(List.of(testSigningKey(keyId)));
        properties.setAuthorityName("local-trust-authority");
        return service(properties, new RecordingAuditSink());
    }

    private TrustAuthorityService service(TrustAuthorityProperties properties, TrustAuthorityAuditSink auditSink) {
        ensureSigningKey(properties);
        return new TrustAuthorityService(properties, auditSink, new TrustAuthorityRateLimiter());
    }

    private TrustAuthorityService service(TrustAuthorityProperties properties, TrustAuthorityAuditSink auditSink, TrustAuthorityMetrics metrics) {
        return service(properties, auditSink, metrics, Clock.systemUTC());
    }

    private TrustAuthorityService service(
            TrustAuthorityProperties properties,
            TrustAuthorityAuditSink auditSink,
            TrustAuthorityMetrics metrics,
            Clock clock
    ) {
        ensureSigningKey(properties);
        return new TrustAuthorityService(
                properties,
                auditSink,
                new TrustAuthorityRateLimiter(),
                new TrustAuthorityRequestReplayGuard(),
                metrics,
                environment("test"),
                clock
        );
    }

    private TrustAuthorityService service(TrustAuthorityProperties properties) {
        return service(properties, new RecordingAuditSink());
    }

    private TrustAuthorityProperties propertiesWithKeys(TrustAuthorityProperties.KeyEntry... keys) {
        TrustAuthorityProperties properties = new TrustAuthorityProperties();
        properties.setKeys(List.of(keys));
        return properties;
    }

    private void ensureSigningKey(TrustAuthorityProperties properties) {
        if (properties.getKeys().isEmpty()) {
            properties.setKeys(List.of(testSigningKey("local-ed25519-key-1")));
        }
    }

    private TrustAuthorityProperties.KeyEntry testSigningKey(String keyId) {
        try {
            return key(keyId, "ACTIVE", keyPair());
        } catch (Exception exception) {
            throw new IllegalStateException("Test signing key could not be generated.", exception);
        }
    }

    private TrustAuthorityProperties.KeyEntry key(String keyId, String status, KeyPair keyPair) {
        TrustAuthorityProperties.KeyEntry entry = new TrustAuthorityProperties.KeyEntry();
        entry.setKeyId(keyId);
        entry.setStatus(status);
        entry.setPublicKey(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        entry.setPrivateKey(Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
        return entry;
    }

    private TrustAuthorityProperties.KeyEntry publicOnlyKey(String keyId, String status, KeyPair keyPair) {
        TrustAuthorityProperties.KeyEntry entry = keyWithoutMaterial(keyId, status);
        entry.setPublicKey(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        return entry;
    }

    private TrustAuthorityProperties.KeyEntry keyWithoutMaterial(String keyId, String status) {
        TrustAuthorityProperties.KeyEntry entry = new TrustAuthorityProperties.KeyEntry();
        entry.setKeyId(keyId);
        entry.setStatus(status);
        return entry;
    }

    private TrustAuthorityProperties.KeyEntry persistedKey(
            String keyId,
            String status,
            Path privateKey,
            Path publicKey
    ) {
        TrustAuthorityProperties.KeyEntry entry = keyWithoutMaterial(keyId, status);
        entry.setPrivateKeyPath(privateKey.toString());
        entry.setPublicKeyPath(publicKey.toString());
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

    private MockEnvironment environment(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
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

    private TrustVerifyResponse verifyWithTimestamp(
            TrustAuthorityService service,
            TrustSignRequest request,
            TrustSignResponse signature,
            Instant signedAt
    ) {
        return verify(service, new TrustVerifyRequest(
                request.purpose(),
                request.payloadHash(),
                request.partitionKey(),
                request.chainPosition(),
                request.anchorId(),
                signature.signature(),
                signature.keyId(),
                signedAt
        ));
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
        KeyPair signingKeyPair = keyPair();
        Path privateKey = tempDir.resolve("ed25519-private.key");
        Path publicKey = tempDir.resolve("ed25519-public.key");
        Files.writeString(privateKey, Base64.getEncoder().encodeToString(signingKeyPair.getPrivate().getEncoded()));
        restrictPrivateKeyPermissions(privateKey);
        Files.writeString(publicKey, Base64.getEncoder().encodeToString(signingKeyPair.getPublic().getEncoded()));
        TrustAuthorityProperties.KeyEntry signingKey = new TrustAuthorityProperties.KeyEntry();
        signingKey.setKeyId("persistent-ed25519-key-1");
        signingKey.setStatus("ACTIVE");
        signingKey.setPrivateKeyPath(privateKey.toString());
        signingKey.setPublicKeyPath(publicKey.toString());
        properties.setKeys(List.of(signingKey));
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
