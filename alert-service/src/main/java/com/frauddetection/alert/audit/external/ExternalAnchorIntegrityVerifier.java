package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.AuditAnchorDocument;
import com.frauddetection.alert.audit.AuditAnchorRepository;
import com.frauddetection.alert.audit.AuditIntegrityViolation;
import com.frauddetection.alert.audit.external.integrity.ExternalAuditIntegrityQuery;
import com.frauddetection.alert.observability.AlertServiceMetrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class ExternalAnchorIntegrityVerifier {

    private static final String SUPPORTED_SCHEMA_VERSION = "1.0";

    private final AuditAnchorRepository anchorRepository;
    private final ExternalAuditAnchorSink sink;
    private final AlertServiceMetrics metrics;
    private final ExternalAuditIntegrityResponseFactory responses;

    ExternalAnchorIntegrityVerifier(
            AuditAnchorRepository anchorRepository,
            ExternalAuditAnchorSink sink,
            AlertServiceMetrics metrics,
            ExternalAuditIntegrityResponseFactory responses
    ) {
        this.anchorRepository = anchorRepository;
        this.sink = sink;
        this.metrics = metrics;
        this.responses = responses;
    }

    ExternalAuditIntegrityResponse verify(ExternalAuditIntegrityQuery query) {
        AuditAnchorDocument local = anchorRepository.findLatestByPartitionKey(query.partitionKey()).orElse(null);
        if (local == null) {
            return responses.noLocalAnchor(query);
        }

        ExternalAuditAnchor external;
        try {
            Optional<ExternalAuditAnchor> exactExternal = sink.findByChainPosition(
                    query.partitionKey(), local.chainPosition());
            if (exactExternal == null) {
                exactExternal = Optional.empty();
            }
            external = exactExternal.or(() -> sink.latest(query.partitionKey())).orElse(null);
        } catch (ExternalAuditAnchorSinkException exception) {
            if (!isExternalIntegrityMismatch(exception.reason())) {
                throw exception;
            }
            metrics.recordExternalTamperingDetected(exception.reason());
            return responses.response(
                    "INVALID", query, local, null,
                    List.of(new AuditIntegrityViolation(exception.reason(), 1, exception.reason())),
                    exception.reason(), "External anchor binding mismatch."
            );
        }
        if (external == null) {
            return responses.partial(
                    query, local, null, "EXTERNAL_ANCHOR_MISSING",
                    "External anchor is missing for the local chain head."
            );
        }

        List<AuditIntegrityViolation> violations = new ArrayList<>();
        if (external.chainPosition() < local.chainPosition()) {
            violations.add(new AuditIntegrityViolation("STALE_EXTERNAL_ANCHOR", 1, "STALE_EXTERNAL_ANCHOR"));
            return responses.response(
                    "PARTIAL", query, local, external, violations, "STALE_EXTERNAL_ANCHOR",
                    "External anchor is behind the local chain head."
            );
        }
        if (external.chainPosition() > local.chainPosition()) {
            violations.add(new AuditIntegrityViolation(
                    "EXTERNAL_CHAIN_POSITION_AHEAD", 1, "EXTERNAL_CHAIN_POSITION_AHEAD"));
        }
        if (external.chainPosition() == local.chainPosition() && !same(external.localAnchorId(), local.anchorId())) {
            violations.add(new AuditIntegrityViolation(
                    "EXTERNAL_LOCAL_ANCHOR_ID_MISMATCH", 1, "EXTERNAL_LOCAL_ANCHOR_ID_MISMATCH"));
        }
        if (!same(external.lastEventHash(), local.lastEventHash())) {
            violations.add(new AuditIntegrityViolation("EXTERNAL_HASH_MISMATCH", 1, "EXTERNAL_HASH_MISMATCH"));
        }
        if (!same(external.hashAlgorithm(), local.hashAlgorithm())) {
            violations.add(new AuditIntegrityViolation(
                    "EXTERNAL_HASH_ALGORITHM_MISMATCH", 1, "EXTERNAL_HASH_ALGORITHM_MISMATCH"));
        }
        if (!SUPPORTED_SCHEMA_VERSION.equals(external.schemaVersion())) {
            violations.add(new AuditIntegrityViolation(
                    "EXTERNAL_SCHEMA_VERSION_UNSUPPORTED", 1, "EXTERNAL_SCHEMA_VERSION_UNSUPPORTED"));
        }

        boolean integrityViolationsPresent = !violations.isEmpty();
        ExternalAuditSignatureEnricher.Enrichment signature = responses.enrich(external);
        boolean manifestDegraded = isManifestDegraded(external) || signature.manifestDegraded();
        if (manifestDegraded) {
            violations.add(new AuditIntegrityViolation(
                    ExternalAuditAnchor.REASON_HEAD_MANIFEST_UPDATE_FAILED,
                    1,
                    ExternalAuditAnchor.REASON_HEAD_MANIFEST_UPDATE_FAILED
            ));
        }

        ExternalSignaturePolicyEvaluator.Result signaturePolicy =
                responses.evaluateSignature(signature.verification());
        if (!"VALID".equals(signaturePolicy.status())) {
            violations.add(new AuditIntegrityViolation(
                    signaturePolicy.reasonCode(), 1, signaturePolicy.reasonCode()));
        }
        String status;
        if (integrityViolationsPresent || "INVALID".equals(signaturePolicy.status())) {
            status = "INVALID";
        } else if (manifestDegraded) {
            status = "PARTIAL";
        } else {
            status = violations.isEmpty() ? "VALID" : signaturePolicy.status();
        }
        String reasonCode = signaturePolicy.reasonCode();
        String message = signaturePolicy.message();
        if (integrityViolationsPresent) {
            reasonCode = violations.getFirst().violationType();
            message = "External audit anchor integrity mismatch.";
        } else if (!"INVALID".equals(signaturePolicy.status()) && manifestDegraded) {
            reasonCode = ExternalAuditAnchor.REASON_HEAD_MANIFEST_UPDATE_FAILED;
            message = "External anchor is durable, but its navigation manifest requires repair.";
        }
        return responses.response(status, query, local, external, signature, violations, reasonCode, message);
    }

    private boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private boolean isManifestDegraded(ExternalAuditAnchor anchor) {
        return anchor != null && ExternalAuditAnchor.MANIFEST_STATUS_FAILED.equals(anchor.manifestStatus());
    }

    private boolean isExternalIntegrityMismatch(String reason) {
        return "EXTERNAL_OBJECT_KEY_MISMATCH".equals(reason)
                || "EXTERNAL_PAYLOAD_HASH_MISMATCH".equals(reason)
                || "EXTERNAL_ANCHOR_ID_MISMATCH".equals(reason)
                || "EXTERNAL_ANCHOR_ID_VERSION_UNSUPPORTED".equals(reason)
                || "MISMATCH".equals(reason);
    }
}
