package com.frauddetection.alert.security.config;

import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.security.auth.AnalystAuthenticationFactory;
import com.frauddetection.alert.security.auth.JwtAnalystAuthenticationConverter;
import com.frauddetection.alert.security.auth.ModelPolicyIamMapping;
import com.frauddetection.alert.security.auth.JwtSecurityProperties;
import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtResourceServerRealDecoderTest {

    private static final String EXPECTED_AUDIENCE = "fraud-detection-platform-api";

    private HttpServer server;
    private RSAKey trustedKey;
    private RSAKey attackerKey;
    private String issuer;
    private String jwkSetUri;

    @BeforeEach
    void startLocalJwksAndDiscoveryFixture() throws Exception {
        trustedKey = new RSAKeyGenerator(2048)
                .keyID("trusted-test-key")
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .generate();
        attackerKey = new RSAKeyGenerator(2048)
                .keyID("attacker-test-key")
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        issuer = "http://127.0.0.1:" + port + "/issuer";
        jwkSetUri = "http://127.0.0.1:" + port + "/jwks";
        String jwks = new JWKSet(trustedKey.toPublicJWK()).toString();
        String discovery = "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + jwkSetUri + "\"}";
        server.createContext("/", exchange -> {
            String response = exchange.getRequestURI().getPath().equals("/jwks") ? jwks : discovery;
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopLocalFixture() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void explicitJwksDecoderValidatesSignatureIssuerAudienceAndTimeClaims() throws Exception {
        JwtDecoder decoder = decoder(false);
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

        Jwt accepted = decoder.decode(rsaToken(issuer, EXPECTED_AUDIENCE, now.plusSeconds(300), null,
                trustedKey, trustedKey.getKeyID()));
        assertThat(accepted.getSubject()).isEqualTo("analyst-1");
        AnalystPrincipal principal = (AnalystPrincipal) new JwtAnalystAuthenticationConverter(
                properties(false), new AnalystAuthenticationFactory(), new ModelPolicyIamMapping(Map.of()))
                .convert(accepted).getPrincipal();
        assertThat(principal.institutionalActorId()).isEqualTo(issuer + "#analyst-1");
        assertThat(principal.readAuditActorId()).isEqualTo(PolicyGovernanceActor.identityId(issuer, "analyst-1"));

        assertThat(decoder.decode(rsaToken(issuer, List.of("other-service", EXPECTED_AUDIENCE),
                now.plusSeconds(300), null, trustedKey, trustedKey.getKeyID())).getAudience())
                .contains("other-service", EXPECTED_AUDIENCE);
        assertThat(decoder.decode(rsaToken(issuer, EXPECTED_AUDIENCE, now.plusSeconds(300), null,
                trustedKey, trustedKey.getKeyID())).getAudience()).containsExactly(EXPECTED_AUDIENCE);

        assertRejected(decoder, rsaToken("http://127.0.0.1:" + server.getAddress().getPort() + "/other-issuer",
                EXPECTED_AUDIENCE, now.plusSeconds(300), null, trustedKey, trustedKey.getKeyID()));
        assertRejected(decoder, rsaToken(issuer, "another-service", now.plusSeconds(300), null,
                trustedKey, trustedKey.getKeyID()));
        assertRejected(decoder, rsaToken(issuer, null, now.plusSeconds(300), null,
                trustedKey, trustedKey.getKeyID(), false));
        assertRejected(decoder, rsaToken(issuer, List.of(), now.plusSeconds(300), null,
                trustedKey, trustedKey.getKeyID()));
        assertRejected(decoder, rsaToken(issuer, List.of(List.of(EXPECTED_AUDIENCE)), now.plusSeconds(300), null,
                trustedKey, trustedKey.getKeyID()));
        assertRejected(decoder, rsaToken(issuer, EXPECTED_AUDIENCE, now.plusSeconds(300), null,
                attackerKey, trustedKey.getKeyID()));
        assertRejected(decoder, rsaToken(issuer, EXPECTED_AUDIENCE, now.minusSeconds(300), null,
                trustedKey, trustedKey.getKeyID()));
        assertRejected(decoder, rsaToken(issuer, EXPECTED_AUDIENCE, now.plusSeconds(300), now.plusSeconds(300),
                trustedKey, trustedKey.getKeyID()));
        assertRejected(decoder, hmacToken(issuer, EXPECTED_AUDIENCE, now.plusSeconds(300)));
        assertRejected(decoder, unsignedToken(issuer, now.plusSeconds(300)));
    }

    @Test
    void issuerDiscoveryDecoderUsesTheSameIssuerAndAudienceValidators() throws Exception {
        JwtDecoder decoder = decoder(true);
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

        assertThat(decoder.decode(rsaToken(issuer, EXPECTED_AUDIENCE, now.plusSeconds(300), null,
                trustedKey, trustedKey.getKeyID())).getSubject()).isEqualTo("analyst-1");
        assertRejected(decoder, rsaToken(issuer, "another-service", now.plusSeconds(300), null,
                trustedKey, trustedKey.getKeyID()));
        assertRejected(decoder, rsaToken("http://127.0.0.1:" + server.getAddress().getPort() + "/other-issuer",
                EXPECTED_AUDIENCE, now.plusSeconds(300), null, trustedKey, trustedKey.getKeyID()));
    }

    private JwtDecoder decoder(boolean issuerDiscovery) {
        return new JwtResourceServerSecurityConfig().jwtDecoder(properties(issuerDiscovery), testEnvironment());
    }

    private JwtSecurityProperties properties(boolean issuerDiscovery) {
        return new JwtSecurityProperties(true, false, issuer, issuerDiscovery ? null : jwkSetUri,
                "sub", "groups", Map.of("fraud-analyst", AnalystRole.ANALYST), Set.of(),
                EXPECTED_AUDIENCE, false);
    }

    private Environment testEnvironment() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});
        return environment;
    }

    private String rsaToken(String tokenIssuer, Object audience, Instant expiresAt, Instant notBefore,
                            RSAKey signingKey, String keyId) throws Exception {
        return rsaToken(tokenIssuer, audience, expiresAt, notBefore, signingKey, keyId, true);
    }

    private String rsaToken(String tokenIssuer, Object audience, Instant expiresAt, Instant notBefore,
                            RSAKey signingKey, String keyId, boolean includeAudience) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(tokenIssuer)
                .subject("analyst-1")
                .issueTime(Date.from(Instant.now().minusSeconds(5)))
                .expirationTime(Date.from(expiresAt))
                .claim("groups", List.of("fraud-analyst"));
        if (includeAudience) {
            claims.claim("aud", audience);
        }
        if (notBefore != null) {
            claims.notBeforeTime(Date.from(notBefore));
        }
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT).keyID(keyId).build(), claims.build());
        token.sign(new RSASSASigner(signingKey));
        return token.serialize();
    }

    private String hmacToken(String tokenIssuer, String audience, Instant expiresAt) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(tokenIssuer)
                .subject("analyst-1")
                .issueTime(Date.from(Instant.now().minusSeconds(5)))
                .expirationTime(Date.from(expiresAt))
                .claim("aud", audience)
                .claim("groups", List.of("fraud-analyst"))
                .build();
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT).keyID(trustedKey.getKeyID()).build(), claims);
        token.sign(new MACSigner(new byte[32]));
        return token.serialize();
    }

    private String unsignedToken(String tokenIssuer, Instant expiresAt) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(tokenIssuer)
                .subject("analyst-1")
                .issueTime(Date.from(Instant.now().minusSeconds(5)))
                .expirationTime(Date.from(expiresAt))
                .claim("aud", EXPECTED_AUDIENCE)
                .claim("groups", List.of("fraud-analyst"))
                .build();
        return new PlainJWT(claims).serialize();
    }

    private void assertRejected(JwtDecoder decoder, String token) {
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }
}
