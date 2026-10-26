package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.AuditAnchorDocument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GovernanceExternalAnchorVerifierTest {
    private final ExternalAuditAnchorSink sink = mock(ExternalAuditAnchorSink.class);
    private final ExternalAuditAnchorPublicationStatusRepository statuses =
            mock(ExternalAuditAnchorPublicationStatusRepository.class);
    private final AuditTrustAuthorityClient trust = mock(AuditTrustAuthorityClient.class);
    private final GovernanceExternalAnchorVerifier verifier =
            new GovernanceExternalAnchorVerifier(sink, statuses, trust);
    private final AuditAnchorDocument local = new AuditAnchorDocument("anchor-1",
            Instant.parse("2026-10-24T12:00:00Z"), "source_service:alert-service",
            "a".repeat(64), null, 1, "SHA-256");

    @Test void missingWitnessCannotBeReportedAsIndependent() {
        when(sink.findByChainPosition(local.partitionKey(), 1)).thenReturn(Optional.empty());
        assertThat(verifier.verify(local).status()).isEqualTo("NOT_VERIFIED");
    }

    @Test void changedWitnessIsInvalid() {
        when(sink.sinkType()).thenReturn("object-store");
        var original = ExternalAuditAnchor.from(local, "object-store");
        var changed = new ExternalAuditAnchor(original.externalAnchorId(), original.anchorIdVersion(),
                original.localAnchorId(), original.partitionKey(), original.chainPosition(),
                "b".repeat(64), original.previousEventHash(), original.hashAlgorithm(),
                original.schemaVersion(), original.createdAt(), original.sinkType(),
                original.publicationStatus(), original.publicationReason(), original.manifestStatus());
        when(sink.findByChainPosition(local.partitionKey(), 1)).thenReturn(Optional.of(changed));
        assertThat(verifier.verify(local).status()).isEqualTo("INVALID");
    }

    @Test void exactSignedWitnessNeedsEnforcedRetentionForStrongWitnessStatus() {
        when(sink.sinkType()).thenReturn("object-store");
        var witness = ExternalAuditAnchor.from(local, "object-store").published(null, null);
        when(sink.findByChainPosition(local.partitionKey(), 1)).thenReturn(Optional.of(witness));
        when(sink.externalReference(witness)).thenReturn(Optional.of(
                new ExternalAnchorReference(local.anchorId(), "key", "anchor-hash", "external-hash", Instant.now())));
        var status = mock(ExternalAuditAnchorPublicationStatusDocument.class);
        when(status.externalPublished()).thenReturn(true);
        when(status.externalPublicationStatus()).thenReturn(ExternalAuditAnchor.STATUS_PUBLISHED);
        when(status.externalKey()).thenReturn("key");
        when(status.anchorHash()).thenReturn("anchor-hash");
        when(status.externalHash()).thenReturn("external-hash");
        when(status.signatureStatus()).thenReturn("SIGNED");
        when(status.signature()).thenReturn("signature");
        when(status.signingKeyId()).thenReturn("key-1");
        when(statuses.findByLocalAnchorId(local.anchorId())).thenReturn(Optional.of(status));
        when(trust.verify(any(), any())).thenReturn(AuditTrustSignatureVerificationResult.valid());
        when(sink.immutabilityLevel()).thenReturn(ExternalImmutabilityLevel.CONFIGURED);
        assertThat(verifier.verify(local).status()).isEqualTo("SIGNED_LOCAL_OR_UNENFORCED");
        when(sink.immutabilityLevel()).thenReturn(ExternalImmutabilityLevel.ENFORCED);
        assertThat(verifier.verify(local).status()).isEqualTo("SIGNED_ENFORCED_WITNESS");
        when(trust.verify(any(), any())).thenReturn(AuditTrustSignatureVerificationResult.invalid("BAD_SIGNATURE"));
        assertThat(verifier.verify(local).status()).isEqualTo("INVALID");
    }
}
