package com.frauddetection.alert.security.internal;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalServiceClientProdGuardTest {

    @Test
    void shouldFailClosedWhenProdProfileDisablesInternalAuthClient() {
        InternalServiceClientProdGuard guard = new InternalServiceClientProdGuard(
                new InternalServiceClientProperties(false, "alert-service", ""),
                environment("production")
        );

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Internal service auth client must be enabled in prod-like profiles.");
    }

    @Test
    void shouldFailClosedWhenProdProfileMissesToken() {
        assertThatThrownBy(() -> new InternalServiceClientProperties(true, "alert-service", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("app.internal-auth.client.token is required when internal auth is enabled")
                .hasMessageNotContaining("secret-token");
    }

    @Test
    void shouldAllowDockerLocalProfileWithExplicitLocalConfiguration() {
        InternalServiceClientProdGuard guard = new InternalServiceClientProdGuard(
                new InternalServiceClientProperties(false, "alert-service", ""),
                environment("docker-local")
        );

        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
    }

    private MockEnvironment environment(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }
}
