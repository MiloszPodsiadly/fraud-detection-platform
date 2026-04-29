package com.frauddetection.trustauthority;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class TrustAuthorityJwtIdentityVerifier {

    private final TrustAuthorityProperties properties;
    private final TrustAuthorityMetrics metrics;
    private final Map<String, RSAPublicKey> keys;

    TrustAuthorityJwtIdentityVerifier(TrustAuthorityProperties properties, TrustAuthorityMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
        this.keys = loadKeys(properties.getJwtIdentity().getKeys());
    }

    CallerAuthorization authorize(TrustAuthorityRequestCredentials credentials, String purpose) {
        if (credentials == null || !StringUtils.hasText(credentials.bearerToken())) {
            return failure(HttpStatus.UNAUTHORIZED, "JWT_MISSING", "INVALID_CLAIM");
        }
        try {
            SignedJWT jwt = SignedJWT.parse(credentials.bearerToken());
            String kid = jwt.getHeader().getKeyID();
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
                return failure(HttpStatus.UNAUTHORIZED, "JWT_ALGORITHM_UNSUPPORTED", "INVALID_CLAIM");
            }
            if (!StringUtils.hasText(kid)) {
                return failure(HttpStatus.UNAUTHORIZED, "JWT_KID_MISSING", "KID_MISMATCH");
            }
            RSAPublicKey key = keys.get(kid);
            if (key == null) {
                return failure(HttpStatus.FORBIDDEN, "JWT_KID_UNKNOWN", "KID_MISMATCH");
            }
            if (!jwt.verify(new RSASSAVerifier(key))) {
                return failure(HttpStatus.UNAUTHORIZED, "JWT_SIGNATURE_INVALID", "INVALID_SIGNATURE");
            }
            TrustAuthorityProperties.JwtIdentityProperties jwtProperties = properties.getJwtIdentity();
            if (!safeEquals(jwtProperties.getIssuer(), jwt.getJWTClaimsSet().getIssuer())) {
                return failure(HttpStatus.UNAUTHORIZED, "JWT_ISSUER_INVALID", "INVALID_CLAIM");
            }
            if (!jwt.getJWTClaimsSet().getAudience().contains(jwtProperties.getAudience())) {
                return failure(HttpStatus.UNAUTHORIZED, "JWT_AUDIENCE_INVALID", "INVALID_CLAIM");
            }
            Date issuedAt = jwt.getJWTClaimsSet().getIssueTime();
            Date expiresAt = jwt.getJWTClaimsSet().getExpirationTime();
            if (issuedAt == null || expiresAt == null) {
                return failure(HttpStatus.UNAUTHORIZED, "JWT_TIME_CLAIMS_MISSING", "INVALID_CLAIM");
            }
            Instant now = Instant.now();
            Instant iat = issuedAt.toInstant();
            Instant exp = expiresAt.toInstant();
            Duration skew = nonNegative(jwtProperties.getClockSkew(), Duration.ofSeconds(30));
            if (exp.isBefore(now.minus(skew))) {
                return failure(HttpStatus.UNAUTHORIZED, "JWT_EXPIRED", "EXPIRED");
            }
            if (iat.isAfter(now.plus(skew))) {
                return failure(HttpStatus.UNAUTHORIZED, "JWT_IAT_FUTURE", "INVALID_CLAIM");
            }
            if (iat.isBefore(now.minus(nonNegative(jwtProperties.getMaxTokenAge(), Duration.ofMinutes(5)).plus(skew)))) {
                return failure(HttpStatus.UNAUTHORIZED, "JWT_TOO_OLD", "INVALID_CLAIM");
            }
            if (!exp.isAfter(iat)) {
                return failure(HttpStatus.UNAUTHORIZED, "JWT_TTL_INVALID", "INVALID_CLAIM");
            }
            if (Duration.between(iat, exp).compareTo(nonNegative(jwtProperties.getMaxTtl(), Duration.ofMinutes(5))) > 0) {
                return failure(HttpStatus.UNAUTHORIZED, "JWT_TTL_TOO_LONG", "INVALID_CLAIM");
            }
            String serviceName = jwt.getJWTClaimsSet().getStringClaim(jwtProperties.getServiceNameClaim());
            if (!StringUtils.hasText(serviceName)) {
                return failure(HttpStatus.UNAUTHORIZED, "JWT_SERVICE_MISSING", "INVALID_CLAIM");
            }
            TrustAuthorityProperties.CallerEntry caller = properties.getCallers().stream()
                    .filter(candidate -> serviceName.equals(candidate.getServiceName()))
                    .findFirst()
                    .orElse(null);
            if (caller == null) {
                return failure(HttpStatus.FORBIDDEN, "CALLER_UNKNOWN", "UNAUTHORIZED_SERVICE");
            }
            if (!caller.getAllowedJwtKeyIds().contains(kid)) {
                metrics.recordJwtServiceMismatch();
                return failure(HttpStatus.FORBIDDEN, "JWT_KEY_SERVICE_MISMATCH", "KID_MISMATCH");
            }
            if (!caller.getAllowedPurposes().contains(purpose)) {
                return failure(HttpStatus.FORBIDDEN, "PURPOSE_UNAUTHORIZED", "UNAUTHORIZED_SERVICE");
            }
            List<String> authorities = authorities(jwt.getJWTClaimsSet().getClaim(jwtProperties.getAuthoritiesClaim()));
            if (!authorities.contains(purpose)) {
                metrics.recordJwtAuthorityMissing();
                return failure(HttpStatus.FORBIDDEN, "JWT_AUTHORITY_MISSING", "AUTHORITY_MISSING");
            }
            return CallerAuthorization.success(
                    caller.getSignRateLimitPerMinute(),
                    TrustAuthorityCallerIdentity.of(serviceName, "jwt", jwt.getJWTClaimsSet().getSubject())
            );
        } catch (Exception exception) {
            return failure(HttpStatus.UNAUTHORIZED, "JWT_INVALID", "INVALID_CLAIM");
        }
    }

    private CallerAuthorization failure(HttpStatus status, String reasonCode, String metricReason) {
        metrics.recordJwtInvalid(metricReason);
        if ("EXPIRED".equals(metricReason)) {
            metrics.recordJwtExpired();
        } else if ("KID_MISMATCH".equals(metricReason)) {
            metrics.recordJwtKidMismatch();
        }
        return CallerAuthorization.failure(status, reasonCode);
    }

    private Map<String, RSAPublicKey> loadKeys(List<TrustAuthorityProperties.JwtKeyEntry> entries) {
        Map<String, RSAPublicKey> loaded = new HashMap<>();
        TrustAuthorityProperties.JwtIdentityProperties jwt = properties.getJwtIdentity();
        if (StringUtils.hasText(jwt.getJwksPath())) {
            loadJwks(jwt.getJwksPath()).forEach(loaded::put);
        }
        entries.stream()
                .filter(entry -> StringUtils.hasText(entry.getKeyId()))
                .forEach(entry -> loaded.put(entry.getKeyId(), loadKey(entry)));
        return Map.copyOf(loaded);
    }

    private Map<String, RSAPublicKey> loadJwks(String jwksPath) {
        try {
            JWKSet jwkSet = JWKSet.load(Path.of(jwksPath).toFile());
            Map<String, RSAPublicKey> loaded = new HashMap<>();
            jwkSet.getKeys().stream()
                    .filter(RSAKey.class::isInstance)
                    .map(RSAKey.class::cast)
                    .filter(key -> StringUtils.hasText(key.getKeyID()))
                    .forEach(key -> {
                        try {
                            loaded.put(key.getKeyID(), key.toRSAPublicKey());
                        } catch (Exception exception) {
                            throw new IllegalStateException("Trust authority JWT JWKS key material could not be initialized.");
                        }
                    });
            return loaded;
        } catch (Exception exception) {
            throw new IllegalStateException("Trust authority JWT JWKS material could not be initialized.");
        }
    }

    private RSAPublicKey loadKey(TrustAuthorityProperties.JwtKeyEntry entry) {
        try {
            String material = StringUtils.hasText(entry.getPublicKey())
                    ? entry.getPublicKey()
                    : Files.readString(Path.of(entry.getPublicKeyPath()));
            String normalized = material
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] der = Base64.getDecoder().decode(normalized);
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception exception) {
            throw new IllegalStateException("Trust authority JWT public key material could not be initialized.");
        }
    }

    private List<String> authorities(Object claim) {
        if (claim instanceof List<?> values) {
            return values.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
        }
        if (claim instanceof String value && StringUtils.hasText(value)) {
            return List.of(value.split("[,\\s]+")).stream()
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
        }
        return new ArrayList<>();
    }

    private Duration nonNegative(Duration duration, Duration fallback) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            return fallback;
        }
        return duration;
    }

    private boolean safeEquals(String left, String right) {
        return StringUtils.hasText(left) && left.equals(right);
    }
}
