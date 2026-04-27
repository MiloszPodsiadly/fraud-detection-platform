package com.frauddetection.alert.audit.trust;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditTrustAttestationConfigurationTest {

    private final AuditTrustAttestationConfiguration configuration = new AuditTrustAttestationConfiguration();

    @Test
    void shouldUseDisabledSignerByDefault() {
        AuditTrustAttestationSigner signer = configuration.auditTrustAttestationSigner(
                new AuditTrustAttestationProperties(),
                new MockEnvironment()
        );

        assertThat(signer.signingEnabled()).isFalse();
        assertThat(signer.mode()).isEqualTo("disabled");
    }

    @Test
    void shouldAllowLocalDevSignerOutsideProdLikeProfiles() {
        AuditTrustAttestationProperties properties = new AuditTrustAttestationProperties();
        properties.getSigning().setMode("local-dev");
        properties.getSigning().setLocalDevKeyId("local-key");
        properties.getSigning().setLocalDevSecret("secret");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");

        AuditTrustAttestationSigner signer = configuration.auditTrustAttestationSigner(properties, environment);

        assertThat(signer.signingEnabled()).isTrue();
        assertThat(signer.mode()).isEqualTo("local-dev");
    }

    @Test
    void shouldRejectLocalDevSignerInProdLikeProfiles() {
        AuditTrustAttestationProperties properties = new AuditTrustAttestationProperties();
        properties.getSigning().setMode("local-dev");
        properties.getSigning().setLocalDevKeyId("local-key");
        properties.getSigning().setLocalDevSecret("secret");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("staging");

        assertThatThrownBy(() -> configuration.auditTrustAttestationSigner(properties, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not allowed in prod-like");
    }

    @Test
    void shouldFailClosedForKmsReadyWithoutAdapter() {
        AuditTrustAttestationProperties properties = new AuditTrustAttestationProperties();
        properties.getSigning().setMode("kms-ready");

        assertThatThrownBy(() -> configuration.auditTrustAttestationSigner(properties, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires a real KMS/HSM adapter");
    }
}
