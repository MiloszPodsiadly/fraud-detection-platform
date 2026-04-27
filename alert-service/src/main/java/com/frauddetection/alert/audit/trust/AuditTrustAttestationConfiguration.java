package com.frauddetection.alert.audit.trust;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

@Configuration
@EnableConfigurationProperties(AuditTrustAttestationProperties.class)
public class AuditTrustAttestationConfiguration {

    private static final Set<String> PROD_LIKE_PROFILES = Set.of("prod", "production", "staging");

    @Bean
    AuditTrustAttestationSigner auditTrustAttestationSigner(
            AuditTrustAttestationProperties properties,
            Environment environment
    ) {
        return switch (normalize(properties.getSigning().getMode())) {
            case "disabled" -> new DisabledAuditTrustAttestationSigner();
            case "local-dev" -> localDevSigner(properties, environment);
            case "kms-ready" -> throw new IllegalStateException("KMS-ready audit trust attestation signing requires a real KMS/HSM adapter.");
            default -> throw new IllegalStateException("Unsupported audit trust attestation signing mode.");
        };
    }

    private AuditTrustAttestationSigner localDevSigner(
            AuditTrustAttestationProperties properties,
            Environment environment
    ) {
        if (prodLikeProfile(environment)) {
            throw new IllegalStateException("Local-dev audit trust attestation signing is not allowed in prod-like profiles.");
        }
        if (!StringUtils.hasText(properties.getSigning().getLocalDevKeyId())
                || !StringUtils.hasText(properties.getSigning().getLocalDevSecret())) {
            throw new IllegalStateException("Local-dev audit trust attestation signing requires key id and secret.");
        }
        return new LocalDevAuditTrustAttestationSigner(
                properties.getSigning().getLocalDevKeyId(),
                properties.getSigning().getLocalDevSecret().getBytes(StandardCharsets.UTF_8)
        );
    }

    private boolean prodLikeProfile(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles())
                .map(this::normalize)
                .anyMatch(PROD_LIKE_PROFILES::contains);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
