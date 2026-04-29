package com.frauddetection.alert.audit.external;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

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
        if (!StringUtils.hasText(properties.getHmacSecret())) {
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
                    if (!StringUtils.hasText(properties.getHmacSecret())) {
                        throw new IllegalStateException("Prod-like alert-service requires audit trust authority HMAC credentials when enabled.");
                    }
                }
            }
        };
    }

    private boolean prodLikeProfile(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles())
                .map(String::toLowerCase)
                .anyMatch(profile -> profile.equals("prod")
                        || profile.equals("production")
                        || profile.equals("staging"));
    }
}
