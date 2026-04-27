package com.frauddetection.alert.audit;

import com.frauddetection.alert.audit.external.ExternalAuditAnchorSink;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorSummary;
import com.frauddetection.alert.audit.external.ExternalAuditIntegrityResponse;
import com.frauddetection.alert.audit.external.ExternalAuditIntegrityService;
import com.frauddetection.alert.audit.trust.DisabledAuditTrustAttestationSigner;
import com.frauddetection.alert.audit.trust.LocalDevAuditTrustAttestationSigner;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditTrustAttestationServiceTest {

    private final AuditIntegrityService internalIntegrityService = mock(AuditIntegrityService.class);
    private final ExternalAuditIntegrityService externalIntegrityService = mock(ExternalAuditIntegrityService.class);
    private final ExternalAuditAnchorSink externalAnchorSink = mock(ExternalAuditAnchorSink.class);

    @Test
    void shouldReportInternalOnlyWhenExternalAnchorsAreDisabled() {
        when(externalAnchorSink.sinkType()).thenReturn("disabled");
        when(internalIntegrityService.verify(null, null, "alert-service", "HEAD", 100))
                .thenReturn(internal("VALID"));
        when(externalIntegrityService.verify("alert-service", 100))
                .thenReturn(partialExternal("EXTERNAL_ANCHOR_MISSING", null));

        AuditTrustAttestationResponse response = service(new DisabledAuditTrustAttestationSigner())
                .attest("alert-service", 100, "HEAD");

        assertThat(response.status()).isEqualTo("AVAILABLE");
        assertThat(response.trustLevel()).isEqualTo(AuditTrustLevel.INTERNAL_ONLY);
        assertThat(response.externalAnchorStatus()).isEqualTo("DISABLED");
        assertThat(response.attestationSignature()).isNull();
        assertThat(response.limitations()).contains("external_anchor_not_valid", "derived_from_fdp19_fdp20_source_of_truth");
    }

    @Test
    void shouldReportPartialExternalWhenConfiguredExternalAnchorIsPartial() {
        when(externalAnchorSink.sinkType()).thenReturn("local-file");
        when(internalIntegrityService.verify(null, null, "alert-service", "HEAD", 100))
                .thenReturn(internal("VALID"));
        when(externalIntegrityService.verify("alert-service", 100))
                .thenReturn(partialExternal("STALE_EXTERNAL_ANCHOR", externalAnchor()));

        AuditTrustAttestationResponse response = service(new DisabledAuditTrustAttestationSigner())
                .attest("alert-service", 100, null);

        assertThat(response.trustLevel()).isEqualTo(AuditTrustLevel.PARTIAL_EXTERNAL);
        assertThat(response.externalAnchorStatus()).isEqualTo("STALE");
        assertThat(response.anchorCoverage().coverageRatio()).isZero();
    }

    @Test
    void shouldReportExternallyAnchoredOnlyWhenExternalIntegrityIsValid() {
        when(externalAnchorSink.sinkType()).thenReturn("local-file");
        when(internalIntegrityService.verify(null, null, "alert-service", "HEAD", 100))
                .thenReturn(internal("VALID"));
        when(externalIntegrityService.verify("alert-service", 100))
                .thenReturn(validExternal());

        AuditTrustAttestationResponse response = service(new DisabledAuditTrustAttestationSigner())
                .attest("alert-service", 100, null);

        assertThat(response.trustLevel()).isEqualTo(AuditTrustLevel.EXTERNALLY_ANCHORED);
        assertThat(response.externalAnchorStatus()).isEqualTo("VALID");
        assertThat(response.latestExternalAnchorReference()).isNotNull();
        assertThat(response.anchorCoverage().coverageRatio()).isEqualTo(1.0d);
    }

    @Test
    void shouldReportSignedAttestationWhenValidExternalAnchorAndSigningEnabled() {
        when(externalAnchorSink.sinkType()).thenReturn("local-file");
        when(internalIntegrityService.verify(null, null, "alert-service", "HEAD", 100))
                .thenReturn(internal("VALID"));
        when(externalIntegrityService.verify("alert-service", 100))
                .thenReturn(validExternal());

        AuditTrustAttestationResponse response = service(localDevSigner())
                .attest("alert-service", 100, null);

        assertThat(response.trustLevel()).isEqualTo(AuditTrustLevel.SIGNED_ATTESTATION);
        assertThat(response.attestationSignature()).isNotBlank();
        assertThat(response.signingKeyId()).isEqualTo("local-key");
        assertThat(response.signingMode()).isEqualTo("local-dev");
    }

    @Test
    void shouldReturnUnavailableWhenInternalIntegrityIsUnavailable() {
        when(externalAnchorSink.sinkType()).thenReturn("local-file");
        when(internalIntegrityService.verify(null, null, "alert-service", "HEAD", 100))
                .thenReturn(new AuditIntegrityResponse(
                        "UNAVAILABLE",
                        0,
                        100,
                        "HEAD",
                        false,
                        false,
                        false,
                        "AUDIT_STORE_UNAVAILABLE",
                        "Audit event store is currently unavailable.",
                        null,
                        null,
                        null,
                        null,
                        List.of()
                ));
        when(externalIntegrityService.verify("alert-service", 100))
                .thenReturn(partialExternal("EXTERNAL_ANCHOR_MISSING", null));

        AuditTrustAttestationResponse response = service(new DisabledAuditTrustAttestationSigner())
                .attest("alert-service", 100, null);

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        assertThat(response.trustLevel()).isEqualTo(AuditTrustLevel.UNAVAILABLE);
    }

    private AuditTrustAttestationService service(com.frauddetection.alert.audit.trust.AuditTrustAttestationSigner signer) {
        return new AuditTrustAttestationService(internalIntegrityService, externalIntegrityService, externalAnchorSink, signer);
    }

    private LocalDevAuditTrustAttestationSigner localDevSigner() {
        return new LocalDevAuditTrustAttestationSigner("local-key", "secret".getBytes(StandardCharsets.UTF_8));
    }

    private AuditIntegrityResponse internal(String status) {
        return new AuditIntegrityResponse(
                status,
                1,
                100,
                "HEAD",
                false,
                false,
                false,
                null,
                null,
                "event-hash",
                "event-hash",
                "source_service:alert-service",
                "event-hash",
                List.of()
        );
    }

    private ExternalAuditIntegrityResponse validExternal() {
        return new ExternalAuditIntegrityResponse(
                "VALID",
                1,
                100,
                "alert-service",
                "source_service:alert-service",
                null,
                null,
                localAnchor(),
                externalAnchor(),
                List.of()
        );
    }

    private ExternalAuditIntegrityResponse partialExternal(String reasonCode, ExternalAuditAnchorSummary externalAnchor) {
        return new ExternalAuditIntegrityResponse(
                "PARTIAL",
                1,
                100,
                "alert-service",
                "source_service:alert-service",
                reasonCode,
                "External anchor is partial.",
                localAnchor(),
                externalAnchor,
                List.of(new AuditIntegrityViolation(reasonCode, 1, reasonCode))
        );
    }

    private ExternalAuditAnchorSummary localAnchor() {
        return new ExternalAuditAnchorSummary(
                "anchor-1",
                null,
                null,
                7,
                "event-hash",
                "SHA-256",
                null,
                Instant.parse("2026-04-27T00:00:00Z"),
                null,
                null
        );
    }

    private ExternalAuditAnchorSummary externalAnchor() {
        return new ExternalAuditAnchorSummary(
                null,
                "external-anchor-1",
                "anchor-1",
                7,
                "event-hash",
                "SHA-256",
                "1.0",
                Instant.parse("2026-04-27T00:00:00Z"),
                "local-file",
                "PUBLISHED"
        );
    }
}
