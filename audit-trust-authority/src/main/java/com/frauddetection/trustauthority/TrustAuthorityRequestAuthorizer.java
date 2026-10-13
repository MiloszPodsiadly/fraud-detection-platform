package com.frauddetection.trustauthority;

import com.frauddetection.trustauthority.api.TrustSignRequest;
import com.frauddetection.trustauthority.api.TrustVerifyRequest;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;

final class TrustAuthorityRequestAuthorizer {

    private static final Duration REQUEST_SIGNATURE_MAX_AGE = Duration.ofMinutes(5);
    private static final Duration REQUEST_SIGNATURE_MAX_SKEW = Duration.ofSeconds(30);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final TrustAuthorityProperties properties;
    private final TrustAuthorityJwtIdentityVerifier jwtIdentityVerifier;
    private final Clock clock;

    TrustAuthorityRequestAuthorizer(
            TrustAuthorityProperties properties,
            TrustAuthorityMetrics metrics,
            Clock clock
    ) {
        this.properties = properties;
        this.jwtIdentityVerifier = new TrustAuthorityJwtIdentityVerifier(properties, metrics);
        this.clock = clock;
    }

    CallerAuthorization authorizeSign(TrustAuthorityRequestCredentials credentials, TrustSignRequest request) {
        return authorize(credentials, "SIGN", signCredentialPayload(request), request.purpose());
    }

    CallerAuthorization authorizeVerify(TrustAuthorityRequestCredentials credentials, TrustVerifyRequest request) {
        return authorize(credentials, "VERIFY", verifyCredentialPayload(request), request.purpose());
    }

    CallerAuthorization authorizeAuditIntegrity(
            TrustAuthorityRequestCredentials credentials,
            String payload
    ) {
        return authorize(credentials, "AUDIT_INTEGRITY", payload, "AUDIT_INTEGRITY");
    }

    private CallerAuthorization authorize(
            TrustAuthorityRequestCredentials credentials,
            String action,
            String payload,
            String purpose
    ) {
        if (properties.identityModeEnum() == TrustAuthorityIdentityMode.JWT_SERVICE_IDENTITY) {
            return jwtIdentityVerifier.authorize(credentials, purpose);
        }
        TrustAuthorityCallerIdentity caller = safeCaller(credentials);
        if (credentials == null
                || !StringUtils.hasText(credentials.signature())
                || !StringUtils.hasText(credentials.signedAt())
                || !StringUtils.hasText(credentials.requestId())) {
            return failure(HttpStatus.UNAUTHORIZED, "HMAC_CREDENTIALS_MISSING", caller);
        }
        if (!StringUtils.hasText(caller.serviceName()) || !StringUtils.hasText(caller.environment())) {
            return failure(HttpStatus.UNAUTHORIZED, "CALLER_IDENTITY_MISSING", caller);
        }
        TrustAuthorityProperties.CallerEntry entry = callers().stream()
                .filter(candidate -> caller.serviceName().equals(candidate.getServiceName()))
                .findFirst()
                .orElse(null);
        if (entry == null) {
            return failure(HttpStatus.FORBIDDEN, "CALLER_UNKNOWN", caller);
        }
        String secret = hmacSecret(entry);
        if (!StringUtils.hasText(secret)) {
            return failure(HttpStatus.UNAUTHORIZED, "HMAC_SECRET_MISSING", caller);
        }
        Instant signedAt;
        try {
            signedAt = Instant.parse(credentials.signedAt());
        } catch (DateTimeParseException exception) {
            return failure(HttpStatus.UNAUTHORIZED, "HMAC_TIMESTAMP_INVALID", caller);
        }
        Instant now = clock.instant();
        if (signedAt.isAfter(now.plus(REQUEST_SIGNATURE_MAX_SKEW))) {
            return failure(HttpStatus.UNAUTHORIZED, "HMAC_TIMESTAMP_FUTURE", caller);
        }
        if (signedAt.isBefore(now.minus(REQUEST_SIGNATURE_MAX_AGE))) {
            return failure(HttpStatus.UNAUTHORIZED, "HMAC_TIMESTAMP_EXPIRED", caller);
        }
        String expected = hmac(secret, credentialPayload(
                action, caller, credentials.requestId(), credentials.signedAt(), payload));
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                credentials.signature().getBytes(StandardCharsets.UTF_8))) {
            return failure(HttpStatus.UNAUTHORIZED, "HMAC_SIGNATURE_INVALID", caller);
        }
        boolean purposeAllowed = entry.getAllowedPurposes().stream().anyMatch(purpose::equals);
        if (!purposeAllowed) {
            return failure(HttpStatus.FORBIDDEN, "PURPOSE_UNAUTHORIZED", caller);
        }
        return CallerAuthorization.success(entry.getSignRateLimitPerMinute(), caller);
    }

    private CallerAuthorization failure(
            HttpStatus status,
            String reasonCode,
            TrustAuthorityCallerIdentity caller
    ) {
        return new CallerAuthorization(false, false, status, reasonCode, 0, caller);
    }

    private TrustAuthorityCallerIdentity safeCaller(TrustAuthorityRequestCredentials credentials) {
        return credentials == null || credentials.caller() == null
                ? TrustAuthorityCallerIdentity.of(null, null, null)
                : credentials.caller();
    }

    private String hmacSecret(TrustAuthorityProperties.CallerEntry entry) {
        return StringUtils.hasText(entry.getHmacSecret())
                ? entry.getHmacSecret()
                : properties.getHmacSecret();
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

    private String credentialPayload(
            String action,
            TrustAuthorityCallerIdentity caller,
            String requestId,
            String signedAt,
            String payload
    ) {
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
}
