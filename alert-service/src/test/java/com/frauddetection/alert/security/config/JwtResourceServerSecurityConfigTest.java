package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.auth.JwtSecurityProperties;
import com.frauddetection.alert.security.authorization.AnalystRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.core.env.Environment;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtResourceServerSecurityConfigTest {

    private final JwtResourceServerSecurityConfig config = new JwtResourceServerSecurityConfig();

    @Test
    void shouldPreferExplicitJwkSetUriButStillRequireMatchingIssuerWhenBothAreConfigured() throws Exception {
        JwtSecurityProperties properties = new JwtSecurityProperties(
                true,
                false,
                "https://issuer.example.test/realms/fraud-detection",
                "https://jwks.example.test/realms/fraud-detection/protocol/openid-connect/certs",
                "sub",
                "groups",
                Map.of("fraud-analyst", AnalystRole.ANALYST),
                Set.of(),
                "fraud-detection-platform-api",
                false
        );

        NimbusJwtDecoder decoder = (NimbusJwtDecoder) config.jwtDecoder(properties, environment("test"));
        OAuth2TokenValidator<Jwt> validator = extractValidator(decoder);
        Jwt invalidIssuerJwt = jwt("https://different-issuer.example.test/realms/fraud-detection", "fraud-detection-platform-api");
        Jwt invalidAudienceJwt = jwt("https://issuer.example.test/realms/fraud-detection", "other-service");
        Jwt validIssuerJwt = jwt("https://issuer.example.test/realms/fraud-detection", "fraud-detection-platform-api");

        assertThat(validator.validate(invalidIssuerJwt).hasErrors()).isTrue();
        assertThat(validator.validate(invalidAudienceJwt).hasErrors()).isTrue();
        assertThat(validator.validate(validIssuerJwt).hasErrors()).isFalse();
    }

    @Test
    void shouldRejectJwksOnlyModeUnlessExplicitlyOptedIn() {
        JwtSecurityProperties properties = properties(null, "https://jwks.example.test/keys", false);

        assertThatThrownBy(() -> config.jwtDecoder(properties, environment("test")))
                .isInstanceOf(org.springframework.beans.factory.BeanCreationException.class)
                .hasMessageContaining("app.security.jwt.allow-unbound-jwks-for-local=true");
    }

    @Test
    void shouldRejectUnboundJwksEvenWhenOptedInOutsideLocalProfiles() {
        JwtSecurityProperties properties = properties(null, "https://jwks.example.test/keys", true);

        assertThatThrownBy(() -> config.jwtDecoder(properties, environment("prod")))
                .isInstanceOf(org.springframework.beans.factory.BeanCreationException.class)
                .hasMessageContaining("local/dev/docker-local/bank-local/test");
    }

    @Test
    void shouldAllowExplicitUnboundJwksFixtureOnlyInTestProfile() {
        JwtSecurityProperties properties = properties(null, "https://jwks.example.test/keys", true);

        assertThat(config.jwtDecoder(properties, environment("test"))).isInstanceOf(NimbusJwtDecoder.class);
    }

    @SuppressWarnings("unchecked")
    private OAuth2TokenValidator<Jwt> extractValidator(NimbusJwtDecoder decoder) throws Exception {
        Field validatorField = NimbusJwtDecoder.class.getDeclaredField("jwtValidator");
        validatorField.setAccessible(true);
        return (OAuth2TokenValidator<Jwt>) validatorField.get(decoder);
    }

    private Jwt jwt(String issuer, String audience) {
        return new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "none"),
                Map.of("iss", issuer, "sub", "analyst-1", "aud", java.util.List.of(audience),
                        "groups", java.util.List.of("fraud-analyst"))
        );
    }

    private JwtSecurityProperties properties(String issuer, String jwks, boolean allowUnbound) {
        return new JwtSecurityProperties(true, false, issuer, jwks, "sub", "groups",
                Map.of("fraud-analyst", AnalystRole.ANALYST), Set.of(), null, allowUnbound);
    }

    private Environment environment(String... profiles) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(profiles);
        return environment;
    }
}
