package com.frauddetection.alert.audit.external;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

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
        if (!StringUtils.hasText(properties.getInternalToken())) {
            throw new IllegalStateException("Audit trust authority internal token is required when enabled.");
        }
        return new HttpAuditTrustAuthorityClient(
                restClientBuilder.baseUrl(properties.getUrl()).build(),
                properties.getInternalToken()
        );
    }
}
