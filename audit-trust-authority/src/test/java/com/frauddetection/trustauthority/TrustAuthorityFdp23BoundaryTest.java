package com.frauddetection.trustauthority;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustAuthorityFdp23BoundaryTest {

    @Test
    void shouldNotExposeExternalAnchoringRuntimeHookInFdp23() {
        assertThatThrownBy(() -> Class.forName("com.frauddetection.trustauthority.TrustAuthorityExternalAnchorScheduler"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.frauddetection.trustauthority.NoopExternalAnchorPublisher"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.frauddetection.trustauthority.ExternalAnchorPublisher"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void shouldNotExposeTrustAuthorityExternalAnchoringConfigInApplicationYaml() throws Exception {
        String applicationYaml;
        try (var input = getClass().getResourceAsStream("/application.yml")) {
            applicationYaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(applicationYaml).doesNotContain("app.trust-authority.external-anchoring");
        assertThat(applicationYaml).doesNotContain("TRUST_AUTHORITY_EXTERNAL_ANCHORING_ENABLED");
        assertThat(applicationYaml).doesNotContain("external-anchoring:");
    }

    @Test
    void shouldKeepAuditHeadLocalOnlyWithoutExternalAnchoringClaim() {
        TrustAuthorityAuditEvent event = new TrustAuthorityAuditEvent(
                TrustAuthorityAuditEvent.CURRENT_SCHEMA_VERSION,
                "event-1",
                "SIGN",
                "alert-service@jwt#alert-service",
                "alert-service",
                "request-1",
                "AUDIT_ANCHOR",
                "payload-hash",
                "key-1",
                "SUCCESS",
                null,
                java.time.Instant.parse("2026-04-29T10:00:00Z"),
                null,
                "event-hash",
                10L
        );

        TrustAuthorityAuditHeadResponse head = TrustAuthorityAuditHeadResponse.from(event);

        assertThat(head.status()).isEqualTo("AVAILABLE");
        assertThat(head.proofType()).isEqualTo("LOCAL_HASH_CHAIN_HEAD");
        assertThat(head.integrityHint()).isEqualTo("LOCAL_CHAIN_ONLY");
        assertThat(head.source()).isEqualTo("trust-authority-audit");
        assertThat(head.proofType()).doesNotContain("EXTERNAL");
        assertThat(head.integrityHint()).doesNotContain("EXTERNAL");
    }
}
