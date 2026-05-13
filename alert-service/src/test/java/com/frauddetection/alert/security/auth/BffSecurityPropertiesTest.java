package com.frauddetection.alert.security.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BffSecurityPropertiesTest {

    @Test
    void acceptsLocalKeycloakLogoutAndRelativeRedirect() {
        BffSecurityProperties properties = new BffSecurityProperties(
                true,
                "/",
                "http://localhost:8086/realms/fraud-detection/protocol/openid-connect/logout",
                "analyst-console-ui"
        );

        assertThatCode(() -> properties.validate(new MockEnvironment().withProperty("spring.profiles.active", "test")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsBlankProviderLogoutUriWhenBffIsEnabledOutsideLocalProfile() {
        BffSecurityProperties properties = new BffSecurityProperties(true, "/", "", "analyst-console-ui");

        assertThatThrownBy(() -> properties.validate(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider logout URI is required");
    }

    @Test
    void rejectsExternalHttpProviderLogoutUri() {
        BffSecurityProperties properties = new BffSecurityProperties(
                true,
                "/",
                "http://issuer.example.test/realms/fraud-detection/protocol/openid-connect/logout",
                "analyst-console-ui"
        );

        assertThatThrownBy(() -> properties.validate(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must use HTTPS");
    }

    @Test
    void rejectsUntrustedPostLogoutRedirectUri() {
        BffSecurityProperties properties = new BffSecurityProperties(
                true,
                "https://evil.example.test/",
                "https://issuer.example.test/realms/fraud-detection/protocol/openid-connect/logout",
                "analyst-console-ui"
        );

        assertThatThrownBy(() -> properties.validate(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("post logout redirect URI");
    }
}
