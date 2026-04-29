package com.frauddetection.trustauthority;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.Metrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
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
    private static final Duration REQUEST_SIGNATURE_MAX_AGE = Duration.ofMinutes(5);
    private static final Duration REQUEST_SIGNATURE_MAX_SKEW = Duration.ofSeconds(30);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final ObjectMapper CANONICAL_JSON = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private final TrustAuthorityProperties properties;
    private final TrustAuthorityAuditSink auditSink;
    private final TrustAuthorityRateLimiter rateLimiter;
    private final TrustAuthorityRequestReplayGuard replayGuard;
    private final TrustAuthorityMetrics metrics;
    private final TrustAuthorityJwtIdentityVerifier jwtIdentityVerifier;
    private final boolean localKeyGenerationAllowed;
    private final List<RegisteredKey> keys;
    private final RegisteredKey activeKey;

    @Autowired
    public TrustAuthorityService(
            TrustAuthorityProperties properties,
            TrustAuthorityAuditSink auditSink,
            TrustAuthorityRateLimiter rateLimiter,
            TrustAuthorityRequestReplayGuard replayGuard,
            TrustAuthorityMetrics metrics,
            Environment environment
    ) {
        this.properties = properties;
        this.auditSink = auditSink;
        this.rateLimiter = rateLimiter;
        this.replayGuard = replayGuard;
        this.metrics = metrics;
        this.jwtIdentityVerifier = new TrustAuthorityJwtIdentityVerifier(properties, metrics);
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
        this.replayGuard = new TrustAuthorityRequestReplayGuard();
        this.metrics = new TrustAuthorityMetrics(Metrics.globalRegistry);
        this.jwtIdentityVerifier = new TrustAuthorityJwtIdentityVerifier(properties, metrics);
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

    TrustSignResponse sign(TrustAuthorityRequestCredentials credentials, TrustSignRequest request) {
        CallerAuthorization authorization = authorize(credentials, "SIGN", signCredentialPayload(request), request.purpose());
        TrustAuthorityCallerIdentity caller = authorization.identity();
        if (!authorization.allowed()) {
            audit("SIGN", caller, requestId(credentials), request.purpose(), request.payloadHash(), null, "FAILURE", authorization.reasonCode());
            metrics.recordSign("FAILURE");
            throw new TrustAuthorityRequestException(authorization.status(), "Trust authority signing caller is not authorized.");
        }
        if (!rateLimiter.allow(caller.serviceName(), authorization.signRateLimitPerMinute())) {
            audit("SIGN", caller, requestId(credentials), request.purpose(), request.payloadHash(), null, "FAILURE", "RATE_LIMIT_EXCEEDED");
            metrics.recordRateLimit();
            metrics.recordSign("FAILURE");
            throw new TrustAuthorityRequestException(HttpStatus.TOO_MANY_REQUESTS, "Trust authority signing rate limit exceeded.");
        }
        enforceReplay(caller, requestId(credentials), "SIGN", request.purpose(), request.payloadHash(), null);
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
            audit("SIGN", caller, requestId(credentials), request.purpose(), request.payloadHash(), response.keyId(), "SUCCESS", null);
            metrics.recordSign("SUCCESS");
            return response;
        } catch (GeneralSecurityException exception) {
            failureReason = "SIGNATURE_FAILED";
            throw new IllegalStateException("Trust authority signing failed.");
        } finally {
            if (failureReason != null) {
                audit("SIGN", caller, requestId(credentials), request.purpose(), request.payloadHash(), activeKey.keyId(), "FAILURE", failureReason);
                metrics.recordSign("FAILURE");
            }
        }
    }

    TrustVerifyResponse verify(TrustAuthorityRequestCredentials credentials, TrustVerifyRequest request) {
        CallerAuthorization authorization = authorize(credentials, "VERIFY", verifyCredentialPayload(request), request.purpose());
        TrustAuthorityCallerIdentity caller = authorization.identity();
        if (!authorization.tokenValid()) {
            audit("VERIFY", caller, requestId(credentials), request.purpose(), request.payloadHash(), request.keyId(), "FAILURE", authorization.reasonCode());
            metrics.recordVerify("FAILURE");
            throw new TrustAuthorityRequestException(authorization.status(), "Trust authority verify caller is not authenticated.");
        }
        enforceReplay(caller, requestId(credentials), "VERIFY", request.purpose(), request.payloadHash(), request.keyId());
        validatePurpose(request.purpose());
        RegisteredKey key = keys.stream()
                .filter(candidate -> candidate.keyId().equals(request.keyId()))
                .findFirst()
                .orElse(null);
        if (key == null) {
            return verifyFailure(caller, requestId(credentials), request, "UNKNOWN_KEY");
        }
        if ("REVOKED".equals(key.status())) {
            return verifyFailure(caller, requestId(credentials), request, "KEY_REVOKED");
        }
        if (request.signedAt() == null) {
            return verifyFailure(caller, requestId(credentials), request, "SIGNED_AT_MISSING");
        }
        if (request.signedAt().isBefore(key.validFrom())) {
            return verifyFailure(caller, requestId(credentials), request, "KEY_NOT_YET_VALID");
        }
        if (key.validUntil() != null && request.signedAt().isAfter(key.validUntil())) {
            return verifyFailure(caller, requestId(credentials), request, "KEY_EXPIRED");
        }
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key.publicKey());
            verifier.update(canonicalBytes(request.purpose(), request.payloadHash(), request.partitionKey(), request.chainPosition(), request.anchorId()));
            boolean valid = verifier.verify(Base64.getDecoder().decode(request.signature()));
            if (valid) {
                audit("VERIFY", caller, requestId(credentials), request.purpose(), request.payloadHash(), request.keyId(), "SUCCESS", null);
                metrics.recordVerify("SUCCESS");
                return new TrustVerifyResponse("VALID", null);
            }
            return verifyFailure(caller, requestId(credentials), request, "SIGNATURE_INVALID");
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            return verifyFailure(caller, requestId(credentials), request, "SIGNATURE_INVALID");
        }
    }

    List<TrustKeyResponse> keys() {
        return keys.stream()
                .sorted(Comparator.comparing(RegisteredKey::keyId))
                .map(key -> new TrustKeyResponse(
                        key.keyId(),
                        ALGORITHM,
                        Base64.getEncoder().encodeToString(key.publicKey().getEncoded()),
                        fingerprint(key.publicKey().getEncoded()),
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

    TrustAuthorityAuditIntegrityResponse auditIntegrity(TrustAuthorityRequestCredentials credentials, int limit) {
        return auditIntegrity(credentials, limit, "WINDOW");
    }

    TrustAuthorityAuditIntegrityResponse auditIntegrity(TrustAuthorityRequestCredentials credentials, int limit, String mode) {
        CallerAuthorization authorization = authorize(credentials, "AUDIT_INTEGRITY", "trust-authority-audit-integrity", "AUDIT_INTEGRITY");
        TrustAuthorityCallerIdentity caller = authorization.identity();
        if (!authorization.tokenValid()) {
            audit("VERIFY", caller, requestId(credentials), "AUDIT_INTEGRITY", "trust-authority-audit-integrity", null, "FAILURE", authorization.reasonCode());
            throw new TrustAuthorityRequestException(authorization.status(), "Trust authority audit integrity caller is not authenticated.");
        }
        enforceReplay(caller, requestId(credentials), "VERIFY", "AUDIT_INTEGRITY", "trust-authority-audit-integrity", null);
        TrustAuthorityAuditIntegrityResponse rawResponse = auditSink.integrity(limit, mode);
        TrustAuthorityAuditIntegrityResponse response = rawResponse.withDecisionTrace(decisionTrace(true, false, rawResponse));
        metrics.recordAuditIntegrityResult(response.status());
        audit("VERIFY", caller, requestId(credentials), "AUDIT_INTEGRITY", "trust-authority-audit-integrity", null,
                "VALID".equals(response.status()) ? "SUCCESS" : "FAILURE", response.reasonCode());
        return response;
    }

    TrustAuthorityAuditHeadResponse auditHead(TrustAuthorityRequestCredentials credentials) {
        CallerAuthorization authorization = authorize(credentials, "AUDIT_INTEGRITY", "trust-authority-audit-head", "AUDIT_INTEGRITY");
        TrustAuthorityCallerIdentity caller = authorization.identity();
        if (!authorization.tokenValid()) {
            audit("VERIFY", caller, requestId(credentials), "AUDIT_INTEGRITY", "trust-authority-audit-head", null, "FAILURE", authorization.reasonCode());
            throw new TrustAuthorityRequestException(authorization.status(), "Trust authority audit head caller is not authenticated.");
        }
        enforceReplay(caller, requestId(credentials), "VERIFY", "AUDIT_INTEGRITY", "trust-authority-audit-head", null);
        TrustAuthorityAuditHeadResponse response = auditSink.head();
        audit("VERIFY", caller, requestId(credentials), "AUDIT_INTEGRITY", "trust-authority-audit-head", null, "SUCCESS", null);
        return response;
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

    private TrustVerifyResponse verifyFailure(TrustAuthorityCallerIdentity caller, String requestId, TrustVerifyRequest request, String reasonCode) {
        audit("VERIFY", caller, requestId, request.purpose(), request.payloadHash(), request.keyId(), "FAILURE", reasonCode);
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
            String requestId,
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
                TrustAuthorityAuditEvent.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                action,
                safeCaller.auditIdentity(),
                safeCaller.serviceName(),
                requestId,
                purpose,
                payloadHash,
                keyId,
                result,
                reasonCode,
                Instant.now().truncatedTo(ChronoUnit.MILLIS),
                null,
                null,
                null
        ));
    }

    private void enforceReplay(
            TrustAuthorityCallerIdentity caller,
            String requestId,
            String action,
            String purpose,
            String payloadHash,
            String keyId
    ) {
        if (properties.getReplay().modeEnum() == TrustAuthorityReplayMode.DISTRIBUTED_HINT
                && StringUtils.hasText(requestId)
                && auditSink.requestSeen(caller.serviceName(), requestId)) {
            audit(action, caller, requestId, purpose, payloadHash, keyId, "FAILURE", "REPLAY_DETECTED");
            metrics.recordReplayDetected();
            if ("SIGN".equals(action)) {
                metrics.recordSign("FAILURE");
            } else {
                metrics.recordVerify("FAILURE");
            }
            throw new TrustAuthorityRequestException(HttpStatus.CONFLICT, "Trust authority request replay detected.");
        }
        if (!replayGuard.allow(caller.serviceName(), requestId)) {
            audit(action, caller, requestId, purpose, payloadHash, keyId, "FAILURE", "REPLAY_DETECTED");
            metrics.recordReplayDetected();
            if ("SIGN".equals(action)) {
                metrics.recordSign("FAILURE");
            } else {
                metrics.recordVerify("FAILURE");
            }
            throw new TrustAuthorityRequestException(HttpStatus.CONFLICT, "Trust authority request replay detected.");
        }
    }

    private TrustDecisionTrace decisionTrace(boolean identityVerified, boolean signatureVerified, TrustAuthorityAuditIntegrityResponse response) {
        return new TrustDecisionTrace(
                identityVerified,
                signatureVerified,
                chainTrace(response),
                "signingRequired=" + properties.isSigningRequired(),
                response.status()
        );
    }

    private String chainTrace(TrustAuthorityAuditIntegrityResponse response) {
        if ("PARTIAL".equals(response.status()) || response.integrityConfidence() == TrustAuthorityIntegrityConfidence.PARTIAL_BOUNDARY) {
            return "PARTIAL";
        }
        if (response.integrityConfidence() == TrustAuthorityIntegrityConfidence.FULL_CHAIN_VERIFIED) {
            return "FULL";
        }
        return "WINDOW";
    }

    private String requestId(TrustAuthorityRequestCredentials credentials) {
        return credentials == null ? null : credentials.requestId();
    }

    private CallerAuthorization authorize(TrustAuthorityRequestCredentials credentials, String action, String payload, String purpose) {
        TrustAuthorityIdentityMode mode = properties.identityModeEnum();
        if (mode == TrustAuthorityIdentityMode.JWT_SERVICE_IDENTITY) {
            return jwtIdentityVerifier.authorize(credentials, purpose);
        }
        TrustAuthorityCallerIdentity safeCaller = safeCaller(credentials);
        if (credentials == null || !StringUtils.hasText(credentials.signature()) || !StringUtils.hasText(credentials.signedAt())
                || !StringUtils.hasText(credentials.requestId())) {
            return hmacFailure(HttpStatus.UNAUTHORIZED, "HMAC_CREDENTIALS_MISSING", safeCaller);
        }
        if (!StringUtils.hasText(safeCaller.serviceName()) || !StringUtils.hasText(safeCaller.environment())) {
            return hmacFailure(HttpStatus.UNAUTHORIZED, "CALLER_IDENTITY_MISSING", safeCaller);
        }
        TrustAuthorityProperties.CallerEntry entry = callers().stream()
                .filter(candidate -> safeCaller.serviceName().equals(candidate.getServiceName()))
                .findFirst()
                .orElse(null);
        if (entry == null) {
            return hmacFailure(HttpStatus.FORBIDDEN, "CALLER_UNKNOWN", safeCaller);
        }
        String secret = hmacSecret(entry);
        if (!StringUtils.hasText(secret)) {
            return hmacFailure(HttpStatus.UNAUTHORIZED, "HMAC_SECRET_MISSING", safeCaller);
        }
        Instant signedAt;
        try {
            signedAt = Instant.parse(credentials.signedAt());
        } catch (DateTimeParseException exception) {
            return hmacFailure(HttpStatus.UNAUTHORIZED, "HMAC_TIMESTAMP_INVALID", safeCaller);
        }
        Instant now = Instant.now();
        if (signedAt.isAfter(now.plus(REQUEST_SIGNATURE_MAX_SKEW))) {
            return hmacFailure(HttpStatus.UNAUTHORIZED, "HMAC_TIMESTAMP_FUTURE", safeCaller);
        }
        if (signedAt.isBefore(now.minus(REQUEST_SIGNATURE_MAX_AGE))) {
            return hmacFailure(HttpStatus.UNAUTHORIZED, "HMAC_TIMESTAMP_EXPIRED", safeCaller);
        }
        String expected = hmac(secret, credentialPayload(action, safeCaller, credentials.requestId(), credentials.signedAt(), payload));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), credentials.signature().getBytes(StandardCharsets.UTF_8))) {
            return hmacFailure(HttpStatus.UNAUTHORIZED, "HMAC_SIGNATURE_INVALID", safeCaller);
        }
        boolean purposeAllowed = entry.getAllowedPurposes().stream()
                .anyMatch(allowed -> allowed.equals(purpose));
        if (!purposeAllowed) {
            return hmacFailure(HttpStatus.FORBIDDEN, "PURPOSE_UNAUTHORIZED", safeCaller);
        }
        return CallerAuthorization.success(entry.getSignRateLimitPerMinute(), safeCaller);
    }

    private CallerAuthorization hmacFailure(HttpStatus status, String reasonCode, TrustAuthorityCallerIdentity caller) {
        return new CallerAuthorization(false, false, status, reasonCode, 0, caller);
    }

    private TrustAuthorityCallerIdentity safeCaller(TrustAuthorityRequestCredentials credentials) {
        return credentials == null || credentials.caller() == null
                ? TrustAuthorityCallerIdentity.of(null, null, null)
                : credentials.caller();
    }

    private String hmacSecret(TrustAuthorityProperties.CallerEntry entry) {
        if (StringUtils.hasText(entry.getHmacSecret())) {
            return entry.getHmacSecret();
        }
        return properties.getHmacSecret();
    }

    private String signCredentialPayload(TrustSignRequest request) {
        return String.join("\n",
                nullSafe(request.purpose()),
                nullSafe(request.payloadHash()),
                nullSafe(request.partitionKey()),
                Long.toString(request.chainPosition()),
                nullSafe(request.anchorId()));
    }

    private String verifyCredentialPayload(TrustVerifyRequest request) {
        return String.join("\n",
                nullSafe(request.purpose()),
                nullSafe(request.payloadHash()),
                nullSafe(request.partitionKey()),
                Long.toString(request.chainPosition()),
                nullSafe(request.anchorId()),
                nullSafe(request.signature()),
                nullSafe(request.keyId()),
                request.signedAt() == null ? "" : request.signedAt().toString());
    }

    private String credentialPayload(String action, TrustAuthorityCallerIdentity caller, String requestId, String signedAt, String payload) {
        return String.join("\n",
                action,
                nullSafe(caller.serviceName()),
                nullSafe(caller.environment()),
                nullSafe(requestId),
                nullSafe(signedAt),
                payload);
    }

    private String hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Trust authority request credentials could not be verified.");
        }
    }

    private String fingerprint(byte[] publicKey) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Trust authority key fingerprint could not be calculated.");
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private List<TrustAuthorityProperties.CallerEntry> callers() {
        if (!properties.getCallers().isEmpty()) {
            return properties.getCallers();
        }
        TrustAuthorityProperties.CallerEntry alertService = new TrustAuthorityProperties.CallerEntry();
        alertService.setServiceName("alert-service");
        alertService.setHmacSecret(properties.getHmacSecret());
        alertService.setAllowedPurposes(List.of("AUDIT_ANCHOR", "AUDIT_INTEGRITY"));
        alertService.setSignRateLimitPerMinute(1000);
        TrustAuthorityProperties.CallerEntry scoringService = new TrustAuthorityProperties.CallerEntry();
        scoringService.setServiceName("fraud-scoring-service");
        scoringService.setHmacSecret("local-dev-scoring-trust-hmac-secret");
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
