package com.frauddetection.alert.security.internal;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;

import java.text.ParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalServiceClientProdGuardTest {

    @Test
    void shouldFailClosedWhenProdProfileDisablesInternalAuthClient() {
        InternalServiceClientProdGuard guard = new InternalServiceClientProdGuard(
                disabledProperties(),
                environment("production")
        );

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Internal service auth client must be enabled in prod-like profiles.");
    }

    @Test
    void shouldFailClosedWhenProdProfileMissesToken() {
        assertThatThrownBy(() -> new InternalServiceClientProperties(true, "TOKEN_VALIDATOR", "alert-service", " ", false, InternalServiceClientProperties.Jwt.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("app.internal-auth.client.token is required when TOKEN_VALIDATOR is enabled")
                .hasMessageNotContaining("secret-token");
    }

    @Test
    void shouldRequireTokenValidatorCompatibilityOptInForProdProfile() {
        InternalServiceClientProdGuard guard = new InternalServiceClientProdGuard(
                new InternalServiceClientProperties(true, "TOKEN_VALIDATOR", "alert-service", "token", false, InternalServiceClientProperties.Jwt.empty()),
                environment("prod")
        );

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TOKEN_VALIDATOR internal auth client mode requires explicit prod compatibility opt-in.")
                .hasMessageNotContaining("token");
    }

    @Test
    void shouldAllowCompleteJwtServiceIdentityConfigForProdProfile() {
        InternalServiceClientProdGuard guard = new InternalServiceClientProdGuard(
                jwtProperties(),
                environment("production")
        );

        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void shouldAttachJwtAuthorizationHeaderWithoutSharedSecretHeaders() throws ParseException {
        HttpHeaders headers = new HttpHeaders();

        new InternalServiceAuthHeaders(jwtProperties()).apply(headers);

        assertThat(headers.getFirst("Authorization")).startsWith("Bearer ");
        assertThat(headers.containsKey("X-Internal-Service-Name")).isFalse();
        assertThat(headers.containsKey("X-Internal-Service-Token")).isFalse();
        String token = headers.getFirst("Authorization").substring("Bearer ".length());
        SignedJWT jwt = SignedJWT.parse(token);
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("alert-key-1");
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("fraud-platform-local");
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("ml-inference-service");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("service_name")).isEqualTo("alert-service");
        assertThat(jwt.getJWTClaimsSet().getStringListClaim("authorities")).containsExactly("governance-read");
        assertThat(jwt.getJWTClaimsSet().getIssueTime()).isNotNull();
        assertThat(jwt.getJWTClaimsSet().getExpirationTime()).isAfter(jwt.getJWTClaimsSet().getIssueTime());
        assertThat(jwt.getJWTClaimsSet().getExpirationTime()).isBeforeOrEqualTo(
                Date.from(jwt.getJWTClaimsSet().getIssueTime().toInstant().plus(Duration.ofMinutes(5).plusSeconds(1)))
        );
    }

    @Test
    void shouldRejectHs256JwtServiceIdentityForProdProfile() {
        InternalServiceClientProdGuard guard = new InternalServiceClientProdGuard(
                hs256JwtProperties(),
                environment("prod")
        );

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("HS256 internal auth client JWT mode is local compatibility only and is forbidden in prod-like profiles.");
    }

    @Test
    void shouldRequireRs256KidAndPrivateKey() {
        assertThatThrownBy(() -> new InternalServiceClientProperties(
                true,
                "JWT_SERVICE_IDENTITY",
                "alert-service",
                "",
                false,
                new InternalServiceClientProperties.Jwt(
                        "RS256",
                        "fraud-platform-local",
                        "ml-inference-service",
                        "",
                        "",
                        "unused-test-private-key",
                        "",
                        Duration.ofMinutes(5),
                        "governance-read"
                )
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("app.internal-auth.client.jwt issuer, audience, algorithm, key material, ttl, and authorities are required when JWT_SERVICE_IDENTITY is enabled");
    }

    @Test
    void shouldAllowDockerLocalProfileWithExplicitLocalConfiguration() {
        InternalServiceClientProdGuard guard = new InternalServiceClientProdGuard(
                disabledProperties(),
                environment("docker-local")
        );

        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
    }

    private MockEnvironment environment(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }

    private InternalServiceClientProperties jwtProperties() {
        return new InternalServiceClientProperties(
                true,
                "JWT_SERVICE_IDENTITY",
                "alert-service",
                "",
                false,
                new InternalServiceClientProperties.Jwt(
                        "RS256",
                        "fraud-platform-local",
                        "ml-inference-service",
                        "local-dev-jwt-service-secret-32bytes",
                        "alert-key-1",
                        privateKeyPem(),
                        "",
                        Duration.ofMinutes(5),
                        "governance-read"
                )
        );
    }

    private InternalServiceClientProperties hs256JwtProperties() {
        return new InternalServiceClientProperties(
                true,
                "JWT_SERVICE_IDENTITY",
                "alert-service",
                "",
                false,
                new InternalServiceClientProperties.Jwt(
                        "HS256",
                        "fraud-platform-local",
                        "ml-inference-service",
                        "local-dev-jwt-service-secret-32bytes",
                        "",
                        "",
                        "",
                        Duration.ofMinutes(5),
                        "governance-read"
                )
        );
    }

    private String privateKeyPem() {
        try {
            return Files.readString(repoPath("deployment/service-identity/alert-service-private.pem"));
        } catch (IOException exception) {
            throw new IllegalStateException("Test private key is unavailable.");
        }
    }

    private Path repoPath(String relativePath) {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path resolved = candidate.resolve(relativePath);
            if (Files.exists(resolved)) {
                return resolved;
            }
            candidate = candidate.getParent();
        }
        return Path.of(relativePath);
    }

    private InternalServiceClientProperties disabledProperties() {
        return new InternalServiceClientProperties(
                false,
                "DISABLED_LOCAL_ONLY",
                "alert-service",
                "",
                false,
                InternalServiceClientProperties.Jwt.empty()
        );
    }

}
