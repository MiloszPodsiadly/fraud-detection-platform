package com.frauddetection.scoring.config;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.stream.Collectors;

final class InternalServiceCredentialProvider {

    private final InternalServiceClientProperties properties;

    InternalServiceCredentialProvider(InternalServiceClientProperties properties) {
        this.properties = properties;
    }

    String bearerToken() {
        if (!"JWT_SERVICE_IDENTITY".equals(properties.normalizedMode())) {
            return "";
        }
        Instant now = Instant.now();
        long issuedAt = now.getEpochSecond();
        long expiresAt = now.plus(properties.jwt().ttl()).getEpochSecond();
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = base64Url("""
                {"iss":"%s","aud":"%s","iat":%d,"exp":%d,"service_name":"%s","authorities":[%s]}"""
                .formatted(
                        escape(properties.jwt().issuer()),
                        escape(properties.jwt().audience()),
                        issuedAt,
                        expiresAt,
                        escape(properties.normalizedServiceName()),
                        properties.jwtAuthorities().stream()
                                .map(authority -> "\"" + escape(authority) + "\"")
                                .collect(Collectors.joining(","))
                ));
        String signingInput = header + "." + payload;
        return signingInput + "." + sign(signingInput, properties.jwt().secret());
    }

    private static String sign(String signingInput, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("Internal JWT signing is not available.");
        }
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
