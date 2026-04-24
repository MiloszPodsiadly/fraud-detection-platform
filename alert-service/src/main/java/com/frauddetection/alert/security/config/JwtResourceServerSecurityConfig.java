package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.auth.JwtSecurityProperties;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(JwtSecurityProperties.class)
public class JwtResourceServerSecurityConfig {

    @Bean
    @ConditionalOnProperty(prefix = "app.security.jwt", name = "enabled", havingValue = "true")
    JwtDecoder jwtDecoder(JwtSecurityProperties properties) {
        if (StringUtils.hasText(properties.issuerUri())) {
            if (StringUtils.hasText(properties.jwkSetUri())) {
                NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri().trim()).build();
                OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> issuerValidator =
                        JwtValidators.createDefaultWithIssuer(properties.issuerUri().trim());
                decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerValidator));
                return decoder;
            }
            return JwtDecoders.fromIssuerLocation(properties.issuerUri().trim());
        }
        if (StringUtils.hasText(properties.jwkSetUri())) {
            return NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri().trim()).build();
        }
        throw new BeanCreationException(
                "JWT Resource Server is enabled, but neither app.security.jwt.jwk-set-uri nor app.security.jwt.issuer-uri is configured."
        );
    }
}
