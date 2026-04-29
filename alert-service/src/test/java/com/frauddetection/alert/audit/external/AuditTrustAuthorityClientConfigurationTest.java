package com.frauddetection.alert.audit.external;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditTrustAuthorityClientConfigurationTest {

    @TempDir
    Path tempDir;

    private final AuditTrustAuthorityClientConfiguration configuration = new AuditTrustAuthorityClientConfiguration();

    @Test
    void shouldRejectInlineJwtPrivateKeyInProdLikeProfile() {
        AuditTrustAuthorityProperties properties = jwtProperties();
        properties.getJwtIdentity().setPrivateKey("inline-private-key");
        properties.getJwtIdentity().setPrivateKeyPath(tempDir.resolve("alert.key").toString());

        assertThatThrownBy(() -> configuration.auditTrustAuthorityProdGuard(properties, environment("prod")).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forbids inline");
    }

    @Test
    void shouldRejectMissingJwtPrivateKeyPathInProdLikeProfile() {
        AuditTrustAuthorityProperties properties = jwtProperties();

        assertThatThrownBy(() -> configuration.auditTrustAuthorityProdGuard(properties, environment("staging")).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("private key path");
    }

    @Test
    void shouldRejectUnreadableJwtPrivateKeyPathInProdLikeProfile() {
        AuditTrustAuthorityProperties properties = jwtProperties();
        properties.getJwtIdentity().setPrivateKeyPath(tempDir.resolve("missing.key").toString());

        assertThatThrownBy(() -> configuration.auditTrustAuthorityProdGuard(properties, environment("production")).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must exist and be readable");
    }

    @Test
    void shouldAllowFileBackedJwtPrivateKeyInProdLikeProfile() throws Exception {
        Path keyPath = tempDir.resolve("alert.key");
        Files.writeString(keyPath, "private-key");
        AuditTrustAuthorityProperties properties = jwtProperties();
        properties.getJwtIdentity().setPrivateKeyPath(keyPath.toString());

        assertThatCode(() -> configuration.auditTrustAuthorityProdGuard(properties, environment("prod")).run(null))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldAllowInlineJwtPrivateKeyOutsideProdLikeProfiles() {
        AuditTrustAuthorityProperties properties = jwtProperties();
        properties.getJwtIdentity().setPrivateKey("inline-private-key");

        assertThatCode(() -> configuration.auditTrustAuthorityProdGuard(properties, environment("local")).run(null))
                .doesNotThrowAnyException();
    }

    private AuditTrustAuthorityProperties jwtProperties() {
        AuditTrustAuthorityProperties properties = new AuditTrustAuthorityProperties();
        properties.setEnabled(true);
        properties.setSigningRequired(true);
        properties.setIdentityMode("jwt-service-identity");
        properties.getJwtIdentity().setIssuer("issuer-1");
        properties.getJwtIdentity().setAudience("audit-trust-authority");
        properties.getJwtIdentity().setKeyId("alert-key-1");
        return properties;
    }

    private MockEnvironment environment(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }
}
