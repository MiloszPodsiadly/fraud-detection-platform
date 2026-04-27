package com.frauddetection.alert.audit.trust;

import com.frauddetection.alert.audit.external.ExternalAuditAnchorSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
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

    private static final Logger log = LoggerFactory.getLogger(AuditTrustAttestationConfiguration.class);
    private static final Set<String> PROD_LIKE_PROFILES = Set.of("prod", "production", "staging");

    @Bean
    AuditTrustAttestationSigner auditTrustAttestationSigner(
            AuditTrustAttestationProperties properties,
            Environment environment
    ) {
        String mode = normalize(properties.getSigning().getMode());
        validateModeForProfiles(mode, environment);
        AuditTrustAttestationSigner signer = switch (mode) {
            case "disabled" -> new DisabledAuditTrustAttestationSigner();
            case "local-dev" -> localDevSigner(properties, environment);
            case "kms-ready" -> kmsReadySigner(properties, environment);
            default -> throw new IllegalStateException("Unsupported audit trust attestation signing mode.");
        };
        log.info(
                "Audit trust attestation signer configured. signer_mode={} signature_strength={} production_ready={}",
                signer.mode(),
                signer.signatureStrength(),
                productionReady(signer)
        );
        return signer;
    }

    @Bean
    ApplicationRunner auditTrustAttestationExternalAnchorGuard(
            AuditTrustAttestationSigner signer,
            ExternalAuditAnchorSink externalAnchorSink
    ) {
        return args -> {
            if ("disabled".equals(externalAnchorSink.sinkType())) {
                log.warn("Trust attestation relies on FDP-20 external anchors, but sink is disabled.");
            }
            log.info(
                    "Audit trust attestation runtime guard. signer_mode={} signature_strength={} production_ready={}",
                    signer.mode(),
                    signer.signatureStrength(),
                    productionReady(signer)
            );
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

    private AuditTrustAttestationSigner kmsReadySigner(
            AuditTrustAttestationProperties properties,
            Environment environment
    ) {
        if (!environment.getProperty("app.audit.trust.signing.kms-enabled", Boolean.class, false)) {
            throw new IllegalStateException("KMS-ready audit trust attestation signing requires app.audit.trust.signing.kms-enabled=true.");
        }
        throw new IllegalStateException("KMS-ready audit trust attestation signing requires a real KMS/HSM adapter.");
    }

    private void validateModeForProfiles(String mode, Environment environment) {
        if (prodLikeProfile(environment) && !"disabled".equals(mode) && !"kms-ready".equals(mode)) {
            throw new IllegalStateException("Audit trust attestation signer mode is not allowed in prod-like profiles.");
        }
    }

    private boolean productionReady(AuditTrustAttestationSigner signer) {
        return "PRODUCTION_READY".equals(signer.signatureStrength());
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
