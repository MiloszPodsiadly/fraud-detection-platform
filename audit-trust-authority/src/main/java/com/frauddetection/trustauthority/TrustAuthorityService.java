package com.frauddetection.trustauthority;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.Metrics;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class TrustAuthorityService {

    private static final String ALGORITHM = "Ed25519";
    private static final ObjectMapper CANONICAL_JSON = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private final TrustAuthorityProperties properties;
    private final TrustAuthorityAuditSink auditSink;
    private final TrustAuthorityRateLimiter rateLimiter;
    private final TrustAuthorityMetrics metrics;
    private final boolean localKeyGenerationAllowed;
    private final List<RegisteredKey> keys;
    private final RegisteredKey activeKey;

    public TrustAuthorityService(
            TrustAuthorityProperties properties,
            TrustAuthorityAuditSink auditSink,
            TrustAuthorityRateLimiter rateLimiter,
            TrustAuthorityMetrics metrics,
            Environment environment
    ) {
        this.properties = properties;
        this.auditSink = auditSink;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.localKeyGenerationAllowed = localKeyGenerationAllowed(environment);
        this.keys = loadKeys(properties);
        this.activeKey = keys.stream()
                .filter(key -> "ACTIVE".equals(key.status()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Trust authority requires one active signing key."));
        if (activeKey.privateKey() == null) {
            throw new IllegalStateException("Active trust authority key requires private key material.");
        }
    }

    TrustAuthorityService(TrustAuthorityProperties properties, TrustAuthorityAuditSink auditSink, TrustAuthorityRateLimiter rateLimiter) {
        this.properties = properties;
        this.auditSink = auditSink;
        this.rateLimiter = rateLimiter;
        this.metrics = new TrustAuthorityMetrics(Metrics.globalRegistry);
        this.localKeyGenerationAllowed = true;
        this.keys = loadKeys(properties);
        this.activeKey = keys.stream()
                .filter(key -> "ACTIVE".equals(key.status()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Trust authority requires one active signing key."));
        if (activeKey.privateKey() == null) {
            throw new IllegalStateException("Active trust authority key requires private key material.");
        }
    }

    TrustSignResponse sign(String token, TrustAuthorityCallerIdentity caller, TrustSignRequest request) {
        CallerAuthorization authorization = authorize(token, caller, request.purpose());
        if (!authorization.allowed()) {
            audit("SIGN", caller, request.purpose(), request.payloadHash(), null, "FAILURE", authorization.reasonCode());
            metrics.recordSign("FAILURE");
            throw new TrustAuthorityRequestException(authorization.status(), "Trust authority signing caller is not authorized.");
        }
        if (!rateLimiter.allow(caller.auditIdentity(), authorization.signRateLimitPerMinute())) {
            audit("SIGN", caller, request.purpose(), request.payloadHash(), null, "FAILURE", "RATE_LIMIT_EXCEEDED");
            metrics.recordRateLimit();
            metrics.recordSign("FAILURE");
            throw new TrustAuthorityRequestException(HttpStatus.TOO_MANY_REQUESTS, "Trust authority signing rate limit exceeded.");
        }
        String failureReason = null;
        try {
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(activeKey.privateKey());
            signer.update(canonicalBytes(request.purpose(), request.payloadHash(), request.partitionKey(), request.chainPosition(), request.anchorId()));
            TrustSignResponse response = new TrustSignResponse(
                    Base64.getEncoder().encodeToString(signer.sign()),
                    activeKey.keyId(),
                    ALGORITHM,
                    Instant.now(),
                    properties.getAuthorityName()
            );
            audit("SIGN", caller, request.purpose(), request.payloadHash(), response.keyId(), "SUCCESS", null);
            metrics.recordSign("SUCCESS");
            return response;
        } catch (GeneralSecurityException exception) {
            failureReason = "SIGNATURE_FAILED";
            throw new IllegalStateException("Trust authority signing failed.");
        } finally {
            if (failureReason != null) {
                audit("SIGN", caller, request.purpose(), request.payloadHash(), activeKey.keyId(), "FAILURE", failureReason);
                metrics.recordSign("FAILURE");
            }
        }
    }

    TrustVerifyResponse verify(String token, TrustAuthorityCallerIdentity caller, TrustVerifyRequest request) {
        CallerAuthorization authorization = authorize(token, caller, request.purpose());
        if (!authorization.tokenValid()) {
            audit("VERIFY", caller, request.purpose(), request.payloadHash(), request.keyId(), "FAILURE", authorization.reasonCode());
            metrics.recordVerify("FAILURE");
            throw new TrustAuthorityRequestException(authorization.status(), "Trust authority verify caller is not authenticated.");
        }
        validatePurpose(request.purpose());
        RegisteredKey key = keys.stream()
                .filter(candidate -> candidate.keyId().equals(request.keyId()))
                .findFirst()
                .orElse(null);
        if (key == null) {
            return verifyFailure(caller, request, "UNKNOWN_KEY");
        }
        if ("REVOKED".equals(key.status())) {
            return verifyFailure(caller, request, "KEY_REVOKED");
        }
        if (request.signedAt() == null) {
            return verifyFailure(caller, request, "SIGNED_AT_MISSING");
        }
        if (request.signedAt().isBefore(key.validFrom())) {
            return verifyFailure(caller, request, "KEY_NOT_YET_VALID");
        }
        if (key.validUntil() != null && request.signedAt().isAfter(key.validUntil())) {
            return verifyFailure(caller, request, "KEY_EXPIRED");
        }
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key.publicKey());
            verifier.update(canonicalBytes(request.purpose(), request.payloadHash(), request.partitionKey(), request.chainPosition(), request.anchorId()));
            boolean valid = verifier.verify(Base64.getDecoder().decode(request.signature()));
            if (valid) {
                audit("VERIFY", caller, request.purpose(), request.payloadHash(), request.keyId(), "SUCCESS", null);
                metrics.recordVerify("SUCCESS");
                return new TrustVerifyResponse("VALID", null);
            }
            return verifyFailure(caller, request, "SIGNATURE_INVALID");
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            return verifyFailure(caller, request, "SIGNATURE_INVALID");
        }
    }

    TrustSignResponse sign(TrustSignRequest request) {
        return sign(properties.getInternalToken(), TrustAuthorityCallerIdentity.of("alert-service", "local", null), request);
    }

    TrustVerifyResponse verify(TrustVerifyRequest request) {
        return verify(properties.getInternalToken(), TrustAuthorityCallerIdentity.of("alert-service", "local", null), request);
    }

    List<TrustKeyResponse> keys() {
        return keys.stream()
                .sorted(Comparator.comparing(RegisteredKey::keyId))
                .map(key -> new TrustKeyResponse(
                        key.keyId(),
                        ALGORITHM,
                        Base64.getEncoder().encodeToString(key.publicKey().getEncoded()),
                        key.validFrom(),
                        key.validUntil(),
                        key.status()
                ))
                .toList();
    }

    TrustKeyResponse key() {
        return keys().stream()
                .filter(key -> activeKey.keyId().equals(key.keyId()))
                .findFirst()
                .orElseThrow();
    }

    private List<RegisteredKey> loadKeys(TrustAuthorityProperties properties) {
        if (!properties.getKeys().isEmpty()) {
            return properties.getKeys().stream()
                    .map(this::loadConfiguredKey)
                    .toList();
        }
        return List.of(loadLegacyKey(properties));
    }

    private RegisteredKey loadConfiguredKey(TrustAuthorityProperties.KeyEntry entry) {
        if (!ALGORITHM.equals(entry.getAlgorithm())) {
            throw new IllegalStateException("Unsupported trust authority key algorithm.");
        }
        try {
            PublicKey publicKey = publicKey(readKeyMaterial(entry.getPublicKey(), entry.getPublicKeyPath()));
            PrivateKey privateKey = StringUtils.hasText(entry.getPrivateKey()) || StringUtils.hasText(entry.getPrivateKeyPath())
                    ? privateKey(readKeyMaterial(entry.getPrivateKey(), entry.getPrivateKeyPath()))
                    : null;
            return new RegisteredKey(
                    entry.getKeyId(),
                    publicKey,
                    privateKey,
                    normalizeStatus(entry.getStatus()),
                    entry.getValidFrom() == null ? Instant.EPOCH : entry.getValidFrom(),
                    entry.getValidUntil()
            );
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException("Trust authority configured key material could not be initialized.", exception);
        }
    }

    private RegisteredKey loadLegacyKey(TrustAuthorityProperties properties) {
        try {
            KeyPair keyPair = loadOrGenerateKeyPair(properties);
            return new RegisteredKey(
                    properties.getKeyId(),
                    keyPair.getPublic(),
                    keyPair.getPrivate(),
                    "ACTIVE",
                    Instant.EPOCH,
                    null
            );
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException("Trust authority key material could not be initialized.", exception);
        }
    }

    private byte[] canonicalBytes(String purpose, String payloadHash, String partitionKey, long chainPosition, String anchorId) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("anchor_id", anchorId);
        canonical.put("chain_position", chainPosition);
        canonical.put("partition_key", partitionKey);
        canonical.put("payload_hash", payloadHash);
        canonical.put("purpose", purpose);
        try {
            return CANONICAL_JSON.writeValueAsBytes(canonical);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Trust authority canonical payload could not be serialized.");
        }
    }

    private void validatePurpose(String purpose) {
        if (!"AUDIT_ANCHOR".equals(purpose)) {
            throw new TrustAuthorityRequestException(HttpStatus.FORBIDDEN, "Unsupported trust signing purpose.");
        }
    }

    private KeyPair loadOrGenerateKeyPair(TrustAuthorityProperties properties) throws IOException, GeneralSecurityException {
        if (StringUtils.hasText(properties.getPrivateKeyPath()) && Files.exists(Path.of(properties.getPrivateKeyPath()))) {
            PrivateKey privateKey = privateKey(Files.readString(Path.of(properties.getPrivateKeyPath())));
            PublicKey publicKey = StringUtils.hasText(properties.getPublicKeyPath()) && Files.exists(Path.of(properties.getPublicKeyPath()))
                    ? publicKey(Files.readString(Path.of(properties.getPublicKeyPath())))
                    : null;
            if (publicKey == null) {
                throw new IllegalStateException("Trust authority public key path is required when loading a private key.");
            }
            return new KeyPair(publicKey, privateKey);
        }
        if (!localKeyGenerationAllowed) {
            throw new IllegalStateException("Trust authority key generation is allowed only for local/dev/test profiles.");
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
        KeyPair generated = generator.generateKeyPair();
        writeKeysIfConfigured(properties, generated);
        return generated;
    }

    private void writeKeysIfConfigured(TrustAuthorityProperties properties, KeyPair generated) throws IOException {
        if (StringUtils.hasText(properties.getPrivateKeyPath())) {
            Path path = Path.of(properties.getPrivateKeyPath());
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, Base64.getEncoder().encodeToString(generated.getPrivate().getEncoded()));
        }
        if (StringUtils.hasText(properties.getPublicKeyPath())) {
            Path path = Path.of(properties.getPublicKeyPath());
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, Base64.getEncoder().encodeToString(generated.getPublic().getEncoded()));
        }
    }

    private String readKeyMaterial(String inline, String path) throws IOException {
        if (StringUtils.hasText(inline)) {
            return inline;
        }
        if (StringUtils.hasText(path)) {
            return Files.readString(Path.of(path));
        }
        throw new IllegalStateException("Configured trust authority key material is missing.");
    }

    private PrivateKey privateKey(String encoded) throws GeneralSecurityException {
        return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded.trim())));
    }

    private PublicKey publicKey(String encoded) throws GeneralSecurityException {
        return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded.trim())));
    }

    private String normalizeStatus(String status) {
        String normalized = StringUtils.hasText(status) ? status.trim().toUpperCase() : "ACTIVE";
        if (!List.of("ACTIVE", "RETIRED", "REVOKED").contains(normalized)) {
            throw new IllegalStateException("Unsupported trust authority key status.");
        }
        return normalized;
    }

    private TrustVerifyResponse verifyFailure(TrustAuthorityCallerIdentity caller, TrustVerifyRequest request, String reasonCode) {
        audit("VERIFY", caller, request.purpose(), request.payloadHash(), request.keyId(), "FAILURE", reasonCode);
        metrics.recordVerify("FAILURE");
        if ("SIGNATURE_INVALID".equals(reasonCode)) {
            metrics.recordInvalidSignature();
        } else if ("UNKNOWN_KEY".equals(reasonCode)) {
            metrics.recordUnknownKey();
        } else if ("KEY_REVOKED".equals(reasonCode)) {
            metrics.recordRevokedKey();
        }
        return new TrustVerifyResponse("INVALID", reasonCode);
    }

    private void audit(
            String action,
            TrustAuthorityCallerIdentity caller,
            String purpose,
            String payloadHash,
            String keyId,
            String result,
            String reasonCode
    ) {
        TrustAuthorityCallerIdentity safeCaller = caller == null
                ? TrustAuthorityCallerIdentity.of(null, null, null)
                : caller;
        auditSink.append(new TrustAuthorityAuditEvent(
                UUID.randomUUID().toString(),
                action,
                safeCaller.auditIdentity(),
                safeCaller.serviceName(),
                purpose,
                payloadHash,
                keyId,
                result,
                reasonCode,
                Instant.now()
        ));
    }

    private CallerAuthorization authorize(String token, TrustAuthorityCallerIdentity caller, String purpose) {
        TrustAuthorityCallerIdentity safeCaller = caller == null
                ? TrustAuthorityCallerIdentity.of(null, null, null)
                : caller;
        if (!StringUtils.hasText(token)) {
            return CallerAuthorization.failure(HttpStatus.UNAUTHORIZED, "TOKEN_MISSING");
        }
        TrustAuthorityProperties.CallerEntry entry = callers().stream()
                .filter(candidate -> safeCaller.serviceName().equals(candidate.getServiceName()))
                .findFirst()
                .orElse(null);
        if (entry == null) {
            return CallerAuthorization.failure(HttpStatus.FORBIDDEN, "CALLER_UNKNOWN");
        }
        String expectedToken = StringUtils.hasText(entry.getInternalToken())
                ? entry.getInternalToken()
                : properties.getInternalToken();
        if (!StringUtils.hasText(expectedToken) || !expectedToken.equals(token)) {
            return CallerAuthorization.failure(HttpStatus.UNAUTHORIZED, "TOKEN_CALLER_MISMATCH");
        }
        boolean purposeAllowed = entry.getAllowedPurposes().stream()
                .anyMatch(allowed -> allowed.equals(purpose));
        if (!purposeAllowed) {
            return CallerAuthorization.failure(HttpStatus.FORBIDDEN, "PURPOSE_UNAUTHORIZED");
        }
        return CallerAuthorization.success(entry.getSignRateLimitPerMinute());
    }

    private List<TrustAuthorityProperties.CallerEntry> callers() {
        if (!properties.getCallers().isEmpty()) {
            return properties.getCallers();
        }
        TrustAuthorityProperties.CallerEntry alertService = new TrustAuthorityProperties.CallerEntry();
        alertService.setServiceName("alert-service");
        alertService.setInternalToken(properties.getInternalToken());
        alertService.setAllowedPurposes(List.of("AUDIT_ANCHOR"));
        alertService.setSignRateLimitPerMinute(1000);
        TrustAuthorityProperties.CallerEntry scoringService = new TrustAuthorityProperties.CallerEntry();
        scoringService.setServiceName("fraud-scoring-service");
        scoringService.setInternalToken(properties.getInternalToken());
        scoringService.setAllowedPurposes(List.of());
        scoringService.setSignRateLimitPerMinute(0);
        return List.of(alertService, scoringService);
    }

    private boolean localKeyGenerationAllowed(Environment environment) {
        Set<String> profiles = Set.of(environment.getActiveProfiles());
        return profiles.isEmpty()
                || profiles.contains("local")
                || profiles.contains("dev")
                || profiles.contains("test")
                || profiles.contains("docker-local");
    }

    private record CallerAuthorization(
            boolean tokenValid,
            boolean allowed,
            HttpStatus status,
            String reasonCode,
            int signRateLimitPerMinute
    ) {
        static CallerAuthorization success(int signRateLimitPerMinute) {
            return new CallerAuthorization(true, true, HttpStatus.OK, null, signRateLimitPerMinute);
        }

        static CallerAuthorization failure(HttpStatus status, String reasonCode) {
            return new CallerAuthorization(false, false, status, reasonCode, 0);
        }
    }

    private record RegisteredKey(
            String keyId,
            PublicKey publicKey,
            PrivateKey privateKey,
            String status,
            Instant validFrom,
            Instant validUntil
    ) {
    }
}
