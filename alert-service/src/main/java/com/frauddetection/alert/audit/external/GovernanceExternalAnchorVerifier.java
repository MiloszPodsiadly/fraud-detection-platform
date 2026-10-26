package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.AuditAnchorDocument;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Verifies the exact witness for a governance event, without trusting local publication status alone. */
@Service
public class GovernanceExternalAnchorVerifier {
    private final ExternalAuditAnchorSink sink;
    private final ExternalAuditAnchorPublicationStatusRepository statuses;
    private final AuditTrustAuthorityClient trust;

    GovernanceExternalAnchorVerifier(ExternalAuditAnchorSink sink,
            ExternalAuditAnchorPublicationStatusRepository statuses, AuditTrustAuthorityClient trust) {
        this.sink = sink;
        this.statuses = statuses;
        this.trust = trust;
    }

    public Result verify(AuditAnchorDocument local) {
        try {
            var witness = sink.findByChainPosition(local.partitionKey(), local.chainPosition()).orElse(null);
            if (witness == null) return new Result("NOT_VERIFIED", null);
            if (!Objects.equals(witness.localAnchorId(), local.anchorId())
                    || !Objects.equals(witness.partitionKey(), local.partitionKey())
                    || witness.chainPosition() != local.chainPosition()
                    || !Objects.equals(witness.lastEventHash(), local.lastEventHash())
                    || !Objects.equals(witness.previousEventHash(), local.previousEventHash())
                    || !Objects.equals(witness.hashAlgorithm(), local.hashAlgorithm())
                    || !Objects.equals(witness.externalAnchorId(), ExternalAuditAnchor.from(local, sink.sinkType()).externalAnchorId())) {
                return new Result("INVALID", "EXTERNAL_ANCHOR_MISMATCH");
            }
            if (!ExternalAuditAnchor.STATUS_PUBLISHED.equals(witness.publicationStatus())
                    || ExternalAuditAnchor.MANIFEST_STATUS_FAILED.equals(witness.manifestStatus())) {
                return new Result("NOT_VERIFIED", "EXTERNAL_PUBLICATION_INCOMPLETE");
            }
            var reference = sink.externalReference(witness).orElse(null);
            var status = statuses.findByLocalAnchorId(local.anchorId()).orElse(null);
            if (reference == null || status == null || !status.externalPublished()
                    || !ExternalAuditAnchor.STATUS_PUBLISHED.equals(status.externalPublicationStatus())) {
                return new Result("NOT_VERIFIED", null);
            }
            if (!Objects.equals(reference.anchorId(), local.anchorId())
                    || !Objects.equals(reference.externalKey(), status.externalKey())
                    || !Objects.equals(reference.anchorHash(), status.anchorHash())
                    || !Objects.equals(reference.externalHash(), status.externalHash())) {
                return new Result("INVALID", "EXTERNAL_REFERENCE_MISMATCH");
            }
            if (ExternalAuditAnchor.MANIFEST_STATUS_FAILED.equals(status.headManifestStatus())
                    || ExternalAuditAnchor.MANIFEST_STATUS_FAILED.equals(status.manifestStatus())) {
                return new Result("NOT_VERIFIED", "EXTERNAL_MANIFEST_INCOMPLETE");
            }
            if (!"SIGNED".equals(status.signatureStatus()) || status.signature() == null
                    || status.signingKeyId() == null) return new Result("UNSIGNED", null);
            var signature = new SignedAuditAnchorPayload(status.signatureStatus(), status.signingAlgorithm(),
                    status.signature(), status.signingKeyId(), status.signedAt(), status.signingAuthority(),
                    status.signedPayloadHash());
            var payload = new AuditAnchorSigningPayload(witness.partitionKey(), witness.localAnchorId(),
                    witness.chainPosition(), witness.lastEventHash(), reference.externalKey(),
                    reference.externalHash(), sink.immutabilityLevel());
            var verified = trust.verify(payload, signature);
            if (!"VALID".equals(verified.status())) {
                return new Result(verified.status(), verified.reasonCode(), status.signingAuthority(),
                        status.signingKeyId());
            }
            // The issuing authority checks this signature. An independent verifier needs
            // its own trusted public-key set and separately retained witness material.
            return new Result(sink.immutabilityLevel() == ExternalImmutabilityLevel.ENFORCED
                    ? "SIGNED_ENFORCED_WITNESS" : "SIGNED_LOCAL_OR_UNENFORCED", null,
                    status.signingAuthority(), status.signingKeyId());
        } catch (RuntimeException unavailable) {
            return new Result("UNAVAILABLE", "EXTERNAL_VERIFICATION_UNAVAILABLE");
        }
    }

    public record Result(String status, String reason, String signingAuthority, String signingKeyId) {
        public Result(String status, String reason) {
            this(status, reason, null, null);
        }
    }
}
