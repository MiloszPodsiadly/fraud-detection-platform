package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.auth.JwtSecurityProperties;
import com.frauddetection.alert.security.authorization.AnalystRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtResourceServerSecurityConfigTest {

    private final JwtResourceServerSecurityConfig config = new JwtResourceServerSecurityConfig();

    @Test
    void shouldPreferExplicitJwkSetUriButStillRequireMatchingIssuerWhenBothAreConfigured() throws Exception {
        JwtSecurityProperties properties = new JwtSecurityProperties(
                true,
                "https://issuer.example.test/realms/fraud-detection",
                "https://jwks.example.test/realms/fraud-detection/protocol/openid-connect/certs",
                "sub",
                "groups",
                Map.of("fraud-analyst", AnalystRole.ANALYST)
        );

        NimbusJwtDecoder decoder = (NimbusJwtDecoder) config.jwtDecoder(properties);
        OAuth2TokenValidator<Jwt> validator = extractValidator(decoder);
        Jwt invalidIssuerJwt = jwt("https://different-issuer.example.test/realms/fraud-detection");
        Jwt validIssuerJwt = jwt("https://issuer.example.test/realms/fraud-detection");

        assertThat(validator.validate(invalidIssuerJwt).hasErrors()).isTrue();
        assertThat(validator.validate(validIssuerJwt).hasErrors()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private OAuth2TokenValidator<Jwt> extractValidator(NimbusJwtDecoder decoder) throws Exception {
        Field validatorField = NimbusJwtDecoder.class.getDeclaredField("jwtValidator");
        validatorField.setAccessible(true);
        return (OAuth2TokenValidator<Jwt>) validatorField.get(decoder);
    }

    private Jwt jwt(String issuer) {
        return new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "none"),
                Map.of("iss", issuer, "sub", "analyst-1", "groups", java.util.List.of("fraud-analyst"))
        );
    }
}
