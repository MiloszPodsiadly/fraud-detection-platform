package com.frauddetection.alert.audit.external;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

@Configuration
@EnableConfigurationProperties(AuditTrustAuthorityProperties.class)
class AuditTrustAuthorityClientConfiguration {

    @Bean
    AuditTrustAuthorityClient auditTrustAuthorityClient(
            AuditTrustAuthorityProperties properties,
            RestClient.Builder restClientBuilder
    ) {
        if (!properties.isEnabled()) {
            return new DisabledAuditTrustAuthorityClient();
        }
        if (!StringUtils.hasText(properties.getUrl())) {
            throw new IllegalStateException("Audit trust authority URL is required when enabled.");
        }
        if (!jwtServiceIdentity(properties) && !StringUtils.hasText(properties.getHmacSecret())) {
            throw new IllegalStateException("Audit trust authority HMAC secret is required when enabled.");
        }
        return new HttpAuditTrustAuthorityClient(
                restClientBuilder.baseUrl(properties.getUrl()).build(),
                properties
        );
    }

    @Bean
    ApplicationRunner auditTrustAuthorityProdGuard(AuditTrustAuthorityProperties properties, Environment environment) {
        return new ApplicationRunner() {
            @Override
            public void run(ApplicationArguments args) {
                if (properties.isEnabled() && prodLikeProfile(environment)) {
                    if (!properties.isSigningRequired()) {
                        throw new IllegalStateException("Prod-like alert-service requires audit trust authority signing-required=true when trust authority is enabled.");
                    }
                    if (!jwtServiceIdentity(properties)) {
                        throw new IllegalStateException("Prod-like alert-service requires audit trust authority JWT service identity; HMAC local mode is not permitted.");
                    }
                    validateJwtPrivateKey(properties.getJwtIdentity());
                }
            }
        };
    }

    private boolean jwtServiceIdentity(AuditTrustAuthorityProperties properties) {
        return "JWT_SERVICE_IDENTITY".equals(normalizedIdentityMode(properties));
    }

    private String normalizedIdentityMode(AuditTrustAuthorityProperties properties) {
        return properties.getIdentityMode() == null
                ? "HMAC_LOCAL"
                : properties.getIdentityMode().trim().replace('-', '_').toUpperCase();
    }

    private void validateJwtPrivateKey(AuditTrustAuthorityProperties.JwtIdentity jwt) {
        if (StringUtils.hasText(jwt.getPrivateKey())) {
            throw new IllegalStateException("Prod-like alert-service forbids inline audit trust authority JWT private key material.");
        }
        if (!StringUtils.hasText(jwt.getPrivateKeyPath())) {
            throw new IllegalStateException("Prod-like alert-service requires audit trust authority JWT private key path.");
        }
        Path keyPath = Path.of(jwt.getPrivateKeyPath());
        if (!Files.exists(keyPath) || !Files.isReadable(keyPath)) {
            throw new IllegalStateException("Prod-like alert-service audit trust authority JWT private key must exist and be readable.");
        }
    }

    private boolean prodLikeProfile(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles())
                .map(String::toLowerCase)
                .anyMatch(profile -> profile.equals("prod")
                        || profile.equals("production")
                        || profile.equals("staging"));
    }
}
