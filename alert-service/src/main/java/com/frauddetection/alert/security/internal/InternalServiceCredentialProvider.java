package com.frauddetection.alert.security.internal;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
                new JWSHeader.Builder(JWSAlgorithm.HS256)
                        .type(JOSEObjectType.JWT)
                        .build(),
                claims
        );
        try {
            jwt.sign(new MACSigner(properties.jwt().secret().getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Internal JWT signing is not available.");
        }
    }
}
