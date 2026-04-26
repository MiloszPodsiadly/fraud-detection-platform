package com.frauddetection.scoring.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalServiceClientProdGuardTest {

    @Test
    void shouldFailClosedWhenProdProfileDisablesInternalAuthClient() {
        InternalServiceClientProdGuard guard = new InternalServiceClientProdGuard(
                disabledProperties(),
                environment("prod")
        );

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Internal service auth client must be enabled in prod-like profiles.");
    }

    @Test
    void shouldFailClosedWhenProdProfileMissesToken() {
        assertThatThrownBy(() -> new InternalServiceClientProperties(true, "TOKEN_VALIDATOR", "fraud-scoring-service", " ", false, InternalServiceClientProperties.Jwt.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("app.internal-auth.client.token is required when TOKEN_VALIDATOR is enabled")
                .hasMessageNotContaining("secret-token");
    }

    @Test
    void shouldRequireTokenValidatorCompatibilityOptInForProdProfile() {
        InternalServiceClientProdGuard guard = new InternalServiceClientProdGuard(
                new InternalServiceClientProperties(true, "TOKEN_VALIDATOR", "fraud-scoring-service", "token", false, InternalServiceClientProperties.Jwt.empty()),
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
                environment("staging")
        );

        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void shouldAttachJwtAuthorizationHeaderWithoutSharedSecretHeaders() {
        HttpHeaders headers = new HttpHeaders();

        new InternalServiceAuthHeaders(jwtProperties()).apply(headers);

        assertThat(headers.getFirst("Authorization")).startsWith("Bearer ");
        assertThat(headers.containsKey("X-Internal-Service-Name")).isFalse();
        assertThat(headers.containsKey("X-Internal-Service-Token")).isFalse();
        String token = headers.getFirst("Authorization").substring("Bearer ".length());
        String payload = new String(Base64.getUrlDecoder().decode(pad(token.split("\\.")[1])), StandardCharsets.UTF_8);
        assertThat(payload).contains("\"service_name\":\"fraud-scoring-service\"");
        assertThat(payload).contains("\"authorities\":[\"ml-score\"]");
        assertThat(payload).doesNotContain("local-dev-jwt-secret");
    }

    @Test
    void shouldAllowDockerProfileWithExplicitLocalConfiguration() {
        InternalServiceClientProdGuard guard = new InternalServiceClientProdGuard(
                disabledProperties(),
                environment("docker")
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
                "fraud-scoring-service",
                "",
                false,
                new InternalServiceClientProperties.Jwt(
                        "fraud-platform-local",
                        "ml-inference-service",
                        "local-dev-jwt-secret",
                        Duration.ofMinutes(5),
                        "ml-score"
                )
        );
    }

    private InternalServiceClientProperties disabledProperties() {
        return new InternalServiceClientProperties(
                false,
                "DISABLED_LOCAL_ONLY",
                "fraud-scoring-service",
                "",
                false,
                InternalServiceClientProperties.Jwt.empty()
        );
    }

    private byte[] pad(String value) {
        return (value + "=".repeat((4 - value.length() % 4) % 4)).getBytes(StandardCharsets.US_ASCII);
    }
}
