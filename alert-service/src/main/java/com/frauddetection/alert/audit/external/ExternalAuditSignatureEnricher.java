package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.dao.DataAccessException;

final class ExternalAuditSignatureEnricher {

    private final ExternalAuditAnchorSink sink;
    private final ExternalAuditAnchorPublicationStatusRepository publicationStatusRepository;
    private final AuditTrustAuthorityClient trustAuthorityClient;
    private final AlertServiceMetrics metrics;

    ExternalAuditSignatureEnricher(
            ExternalAuditAnchorSink sink,
            ExternalAuditAnchorPublicationStatusRepository publicationStatusRepository,
            AuditTrustAuthorityClient trustAuthorityClient,
            AlertServiceMetrics metrics
    ) {
        this.sink = sink;
        this.publicationStatusRepository = publicationStatusRepository;
        this.trustAuthorityClient = trustAuthorityClient == null
                ? new DisabledAuditTrustAuthorityClient()
                : trustAuthorityClient;
        this.metrics = metrics;
    }

    Enrichment enrich(ExternalAuditAnchor external) {
        try {
            ExternalAnchorReference reference = sink.externalReference(external).orElse(null);
            return enrich(external, reference);
        } catch (ExternalAuditAnchorSinkException exception) {
            if (isExternalIntegrityMismatch(exception.reason())) {
                metrics.recordExternalTamperingDetected(exception.reason());
            }
            throw exception;
        }
    }

    private Enrichment enrich(ExternalAuditAnchor external, ExternalAnchorReference reference) {
        if (publicationStatusRepository == null) {
            return Enrichment.unsigned(reference);
        }
        String localAnchorId = reference != null && reference.anchorId() != null
                ? reference.anchorId()
                : external.localAnchorId();
        if (localAnchorId == null) {
            return Enrichment.unsigned(reference);
        }
        try {
            return publicationStatusRepository.findByLocalAnchorId(localAnchorId)
                    .map(status -> reference == null
                            ? Enrichment.unsigned(null, isManifestDegraded(status))
                            : verifiedReference(external, reference, status))
                    .orElseGet(() -> Enrichment.unsigned(reference));
        } catch (DataAccessException exception) {
            return new Enrichment(
                    reference,
                    AuditTrustSignatureVerificationResult.unavailable(),
                    null,
                    null,
                    null,
                    false
            );
        }
    }

    private Enrichment verifiedReference(
            ExternalAuditAnchor external,
            ExternalAnchorReference reference,
            ExternalAuditAnchorPublicationStatusDocument status
    ) {
        SignedAuditAnchorPayload signature = new SignedAuditAnchorPayload(
                status.signatureStatus(),
                status.signingAlgorithm(),
                status.signature(),
                status.signingKeyId(),
                status.signedAt(),
                status.signingAuthority(),
                status.signedPayloadHash()
        );
        ExternalAnchorReference signedReference = reference.withSignature(signature);
        if (!"SIGNED".equals(signature.signatureStatus())
                || signature.signature() == null
                || signature.keyId() == null) {
            return new Enrichment(
                    signedReference,
                    AuditTrustSignatureVerificationResult.unsigned(),
                    null,
                    null,
                    null,
                    isManifestDegraded(status)
            );
        }
        AuditAnchorSigningPayload payload = new AuditAnchorSigningPayload(
                external.partitionKey(),
                external.localAnchorId(),
                external.chainPosition(),
                external.lastEventHash(),
                reference.externalKey(),
                reference.externalHash(),
                sink.immutabilityLevel()
        );
        AuditTrustSignatureVerificationResult verification = trustAuthorityClient.verify(payload, signature);
        return new Enrichment(
                signedReference,
                verification,
                signature.keyId(),
                signature.signatureAlgorithm(),
                signature.signingAuthority(),
                isManifestDegraded(status)
        );
    }

    private boolean isManifestDegraded(ExternalAuditAnchorPublicationStatusDocument status) {
        return ExternalAuditAnchor.MANIFEST_STATUS_FAILED.equals(status.headManifestStatus())
                || ExternalAuditAnchor.MANIFEST_STATUS_FAILED.equals(status.manifestStatus());
    }

    private boolean isExternalIntegrityMismatch(String reason) {
        return "EXTERNAL_OBJECT_KEY_MISMATCH".equals(reason)
                || "EXTERNAL_PAYLOAD_HASH_MISMATCH".equals(reason)
                || "EXTERNAL_ANCHOR_ID_MISMATCH".equals(reason)
                || "EXTERNAL_ANCHOR_ID_VERSION_UNSUPPORTED".equals(reason)
                || "MISMATCH".equals(reason);
    }

    record Enrichment(
            ExternalAnchorReference reference,
            AuditTrustSignatureVerificationResult verification,
            String signingKeyId,
            String signingAlgorithm,
            String signingAuthority,
            boolean manifestDegraded
    ) {
        static Enrichment unsigned(ExternalAnchorReference reference) {
            return unsigned(reference, false);
        }

        static Enrichment unsigned(ExternalAnchorReference reference, boolean manifestDegraded) {
            return new Enrichment(
                    reference,
                    AuditTrustSignatureVerificationResult.unsigned(),
                    null,
                    null,
                    null,
                    manifestDegraded
            );
        }
    }
}
