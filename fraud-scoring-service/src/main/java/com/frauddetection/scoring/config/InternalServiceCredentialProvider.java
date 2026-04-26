package com.frauddetection.scoring.config;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

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
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.jwt().issuer())
                .audience(properties.jwt().audience())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(properties.jwt().ttl())))
                .claim("service_name", properties.normalizedServiceName())
                .claim("authorities", properties.jwtAuthorities())
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(jwsAlgorithm())
                        .type(JOSEObjectType.JWT)
                        .keyID(properties.jwt().keyId().isBlank() ? null : properties.jwt().keyId())
                        .build(),
                claims
        );
        try {
            if ("RS256".equals(properties.jwt().algorithm())) {
                jwt.sign(new RSASSASigner(loadPrivateKey()));
            } else {
                jwt.sign(new MACSigner(properties.jwt().secret().getBytes(StandardCharsets.UTF_8)));
            }
            return jwt.serialize();
        } catch (Exception exception) {
            throw new IllegalStateException("Internal JWT signing is not available.");
        }
    }

    private JWSAlgorithm jwsAlgorithm() {
        return "RS256".equals(properties.jwt().algorithm()) ? JWSAlgorithm.RS256 : JWSAlgorithm.HS256;
    }

    private RSAPrivateKey loadPrivateKey() throws Exception {
        String pem = properties.jwt().privateKeyPem();
        if (pem.isBlank() && !properties.jwt().privateKeyPath().isBlank()) {
            pem = Files.readString(Path.of(properties.jwt().privateKeyPath()));
        }
        String normalized = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(normalized);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return (RSAPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
