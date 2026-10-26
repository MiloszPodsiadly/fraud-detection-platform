package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.auth.JwtSecurityProperties;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Locale;

@Configuration
@EnableConfigurationProperties(JwtSecurityProperties.class)
public class JwtResourceServerSecurityConfig {

    @Bean
    @ConditionalOnProperty(prefix = "app.security.jwt", name = "enabled", havingValue = "true")
    JwtDecoder jwtDecoder(JwtSecurityProperties properties, Environment environment) {
        if (StringUtils.hasText(properties.issuerUri())) {
            if (StringUtils.hasText(properties.jwkSetUri())) {
                NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
                decoder.setJwtValidator(tokenValidator(properties.issuerUri(), properties.expectedAudience()));
                return decoder;
            }
            JwtDecoder discovered = JwtDecoders.fromIssuerLocation(properties.issuerUri());
            if (!(discovered instanceof NimbusJwtDecoder decoder)) {
                throw new BeanCreationException("Issuer discovery did not create a Nimbus JWT decoder.");
            }
            decoder.setJwtValidator(tokenValidator(properties.issuerUri(), properties.expectedAudience()));
            return decoder;
        }
        if (StringUtils.hasText(properties.jwkSetUri())) {
            if (!properties.allowUnboundJwksForLocal() || !isLocalOrTestOnly(environment)) {
                throw new BeanCreationException("JWT JWKS-only mode requires app.security.jwt.allow-unbound-jwks-for-local=true "
                        + "and only local/dev/docker-local/bank-local/test Spring profiles.");
            }
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
            decoder.setJwtValidator(tokenValidator(null, properties.expectedAudience()));
            return decoder;
        }
        throw new BeanCreationException(
                "JWT Resource Server is enabled, but neither app.security.jwt.jwk-set-uri nor app.security.jwt.issuer-uri is configured."
        );
    }

    private OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> tokenValidator(
            String issuerUri,
            String expectedAudience
    ) {
        OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> defaults = StringUtils.hasText(issuerUri)
                ? JwtValidators.createDefaultWithIssuer(issuerUri)
                : JwtValidators.createDefault();
        if (!StringUtils.hasText(expectedAudience)) {
            return defaults;
        }
        OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> audienceValidator = jwt -> {
            try {
                if (jwt.getAudience() != null && jwt.getAudience().contains(expectedAudience)) {
                    return OAuth2TokenValidatorResult.success();
                }
            } catch (RuntimeException malformedAudience) {
                // Malformed audience claims are rejected as invalid credentials.
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token", "The required audience is missing or invalid.", null));
        };
        return new DelegatingOAuth2TokenValidator<>(defaults, audienceValidator);
    }

    private boolean isLocalOrTestOnly(Environment environment) {
        String[] profiles = environment.getActiveProfiles();
        return profiles.length > 0 && Arrays.stream(profiles)
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .allMatch(profile -> profile.equals("local") || profile.equals("dev")
                        || profile.equals("docker-local") || profile.equals("bank-local")
                        || profile.equals("test"));
    }
}
