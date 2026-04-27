package com.frauddetection.alert.audit;

import com.frauddetection.alert.audit.external.ExternalAuditAnchorSink;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorSummary;
import com.frauddetection.alert.audit.external.ExternalAuditIntegrityResponse;
import com.frauddetection.alert.audit.external.ExternalAuditIntegrityService;
import com.frauddetection.alert.audit.trust.DisabledAuditTrustAttestationSigner;
import com.frauddetection.alert.audit.trust.LocalDevAuditTrustAttestationSigner;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditTrustAttestationServiceTest {

    private final AuditIntegrityService internalIntegrityService = mock(AuditIntegrityService.class);
    private final ExternalAuditIntegrityService externalIntegrityService = mock(ExternalAuditIntegrityService.class);
    private final ExternalAuditAnchorSink externalAnchorSink = mock(ExternalAuditAnchorSink.class);
    private final AuditService auditService = mock(AuditService.class);

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
        assertThat(response.attestationSignatureStrength()).isEqualTo("NONE");
        assertThat(response.externalTrustDependency()).isEqualTo("OPTIONAL");
        assertThat(response.limitations()).contains(
                "external_anchor_not_valid",
                "external_trust_incomplete",
                "derived_from_fdp19_fdp20_source_of_truth"
        );
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
        assertThat(response.attestationSignature()).isNull();
        assertThat(response.attestationSignatureStrength()).isEqualTo("NONE");
        assertThat(response.externalTrustDependency()).isEqualTo("REQUIRED");
    }

    @Test
    void shouldNotUpgradeTrustLevelWhenLocalDevSigningIsEnabled() {
        when(externalAnchorSink.sinkType()).thenReturn("local-file");
        when(internalIntegrityService.verify(null, null, "alert-service", "HEAD", 100))
                .thenReturn(internal("VALID"));
        when(externalIntegrityService.verify("alert-service", 100))
                .thenReturn(validExternal());

        AuditTrustAttestationResponse response = service(localDevSigner())
                .attest("alert-service", 100, null);

        assertThat(response.trustLevel()).isEqualTo(AuditTrustLevel.EXTERNALLY_ANCHORED);
        assertThat(response.attestationSignature()).isNotBlank();
        assertThat(response.signingKeyId()).isEqualTo("local-key");
        assertThat(response.signerMode()).isEqualTo("local-dev");
        assertThat(response.attestationSignatureStrength()).isEqualTo("LOCAL_DEV");
        assertThat(response.limitations()).contains("local_signature_not_external_trust");
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

    @Test
    void shouldIncludeAttestationContextInFingerprint() {
        when(externalAnchorSink.sinkType()).thenReturn("local-file");
        when(internalIntegrityService.verify(null, null, "alert-service", "HEAD", 100))
                .thenReturn(internal("VALID"));
        when(externalIntegrityService.verify("alert-service", 100))
                .thenReturn(validExternal());
        when(internalIntegrityService.verify(null, null, "alert-service", "HEAD", 101))
                .thenReturn(internal("VALID"));
        when(externalIntegrityService.verify("alert-service", 101))
                .thenReturn(validExternal());
        when(internalIntegrityService.verify(null, null, "other-service", "HEAD", 100))
                .thenReturn(internal("VALID"));
        when(externalIntegrityService.verify("other-service", 100))
                .thenReturn(validExternal("other-service", "other-event-hash"));

        AuditTrustAttestationService service = service(new DisabledAuditTrustAttestationSigner());

        AuditTrustAttestationResponse first = service.attest("alert-service", 100, "HEAD");
        AuditTrustAttestationResponse same = service.attest("alert-service", 100, "HEAD");
        AuditTrustAttestationResponse differentLimit = service.attest("alert-service", 101, "HEAD");
        AuditTrustAttestationResponse differentSource = service.attest("other-service", 100, "HEAD");

        assertThat(same.attestationFingerprint()).isEqualTo(first.attestationFingerprint());
        assertThat(differentLimit.attestationFingerprint()).isNotEqualTo(first.attestationFingerprint());
        assertThat(differentSource.attestationFingerprint()).isNotEqualTo(first.attestationFingerprint());
    }

    @Test
    void shouldChangeFingerprintWhenExternalAnchorChanges() {
        when(externalAnchorSink.sinkType()).thenReturn("local-file");
        when(internalIntegrityService.verify(null, null, "alert-service", "HEAD", 100))
                .thenReturn(internal("VALID"));
        when(externalIntegrityService.verify("alert-service", 100))
                .thenReturn(validExternal("alert-service", "event-hash", "external-anchor-1"))
                .thenReturn(validExternal("alert-service", "event-hash", "external-anchor-2"));

        AuditTrustAttestationService service = service(new DisabledAuditTrustAttestationSigner());

        AuditTrustAttestationResponse first = service.attest("alert-service", 100, "HEAD");
        AuditTrustAttestationResponse changedAnchor = service.attest("alert-service", 100, "HEAD");

        assertThat(changedAnchor.attestationFingerprint()).isNotEqualTo(first.attestationFingerprint());
    }

    @Test
    void shouldAuditAttestationEndpointAccess() {
        when(externalAnchorSink.sinkType()).thenReturn("local-file");
        when(internalIntegrityService.verify(null, null, "alert-service", "HEAD", 100))
                .thenReturn(internal("VALID"));
        when(externalIntegrityService.verify("alert-service", 100))
                .thenReturn(validExternal());

        AuditTrustAttestationResponse response = service(new DisabledAuditTrustAttestationSigner())
                .attest("alert-service", 100, "HEAD");

        ArgumentCaptor<AuditEventMetadataSummary> metadata = ArgumentCaptor.forClass(AuditEventMetadataSummary.class);
        verify(auditService).audit(
                eq(AuditAction.READ_AUDIT_TRUST_ATTESTATION),
                eq(AuditResourceType.AUDIT_TRUST_ATTESTATION),
                isNull(),
                isNull(),
                eq("audit-trust-attestation-reader"),
                eq(AuditOutcome.SUCCESS),
                isNull(),
                metadata.capture()
        );
        assertThat(metadata.getValue().sourceService()).isEqualTo("alert-service");
        assertThat(metadata.getValue().limit()).isEqualTo(100);
        assertThat(metadata.getValue().trustLevel()).isEqualTo(response.trustLevel().name());
        assertThat(metadata.getValue().internalIntegrityStatus()).isEqualTo("VALID");
        assertThat(metadata.getValue().externalIntegrityStatus()).isEqualTo("VALID");
        assertThat(metadata.getValue().externalAnchorStatus()).isEqualTo("VALID");
        assertThat(metadata.getValue().attestationFingerprint()).isEqualTo(response.attestationFingerprint());
    }

    private AuditTrustAttestationService service(com.frauddetection.alert.audit.trust.AuditTrustAttestationSigner signer) {
        return new AuditTrustAttestationService(internalIntegrityService, externalIntegrityService, externalAnchorSink, signer, auditService);
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
        return validExternal("alert-service", "event-hash");
    }

    private ExternalAuditIntegrityResponse validExternal(String sourceService, String eventHash) {
        return validExternal(sourceService, eventHash, "external-anchor-1");
    }

    private ExternalAuditIntegrityResponse validExternal(String sourceService, String eventHash, String externalAnchorId) {
        return new ExternalAuditIntegrityResponse(
                "VALID",
                1,
                100,
                sourceService,
                "source_service:" + sourceService,
                null,
                null,
                localAnchor(eventHash),
                externalAnchor(eventHash, externalAnchorId),
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
        return localAnchor("event-hash");
    }

    private ExternalAuditAnchorSummary localAnchor(String eventHash) {
        return new ExternalAuditAnchorSummary(
                "anchor-1",
                null,
                null,
                7,
                eventHash,
                "SHA-256",
                null,
                Instant.parse("2026-04-27T00:00:00Z"),
                null,
                null
        );
    }

    private ExternalAuditAnchorSummary externalAnchor() {
        return externalAnchor("event-hash");
    }

    private ExternalAuditAnchorSummary externalAnchor(String eventHash) {
        return externalAnchor(eventHash, "external-anchor-1");
    }

    private ExternalAuditAnchorSummary externalAnchor(String eventHash, String externalAnchorId) {
        return new ExternalAuditAnchorSummary(
                null,
                externalAnchorId,
                "anchor-1",
                7,
                eventHash,
                "SHA-256",
                "1.0",
                Instant.parse("2026-04-27T00:00:00Z"),
                "local-file",
                "PUBLISHED"
        );
    }
}
