package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.AuditAnchorDocument;
import com.frauddetection.alert.audit.AuditIntegrityViolation;
import com.frauddetection.alert.audit.external.integrity.ExternalAuditIntegrityQuery;
import com.frauddetection.alert.observability.AlertServiceMetrics;

import java.util.List;

final class ExternalAuditIntegrityResponseFactory {

    private final ExternalAuditAnchorSink sink;
    private final ExternalAuditSignatureEnricher signatureEnricher;
    private final ExternalSignaturePolicyEvaluator signaturePolicyEvaluator;
    private final AlertServiceMetrics metrics;

    ExternalAuditIntegrityResponseFactory(
            ExternalAuditAnchorSink sink,
            ExternalAuditSignatureEnricher signatureEnricher,
            ExternalSignaturePolicyEvaluator signaturePolicyEvaluator,
            AlertServiceMetrics metrics
    ) {
        this.sink = sink;
        this.signatureEnricher = signatureEnricher;
        this.signaturePolicyEvaluator = signaturePolicyEvaluator;
        this.metrics = metrics;
    }

    ExternalAuditSignatureEnricher.Enrichment enrich(ExternalAuditAnchor external) {
        return signatureEnricher.enrich(external);
    }

    ExternalSignaturePolicyEvaluator.Result evaluateSignature(AuditTrustSignatureVerificationResult verification) {
        ExternalSignaturePolicyEvaluator.Result result = signaturePolicyEvaluator.evaluate(verification);
        metrics.recordAuditSignatureVerification(verification.status());
        metrics.recordAuditSignaturePolicyResult(result.status());
        return result;
    }

    ExternalAuditIntegrityResponse partial(
            ExternalAuditIntegrityQuery query,
            AuditAnchorDocument local,
            ExternalAuditAnchor external,
            String reasonCode,
            String message
    ) {
        return response(
                "PARTIAL",
                query,
                local,
                external,
                List.of(new AuditIntegrityViolation(reasonCode, 1, reasonCode)),
                reasonCode,
                message
        );
    }

    ExternalAuditIntegrityResponse response(
            String status,
            ExternalAuditIntegrityQuery query,
            AuditAnchorDocument local,
            ExternalAuditAnchor external,
            List<AuditIntegrityViolation> violations,
            String reasonCode,
            String message
    ) {
        ExternalAuditSignatureEnricher.Enrichment signature = external == null
                ? ExternalAuditSignatureEnricher.Enrichment.unsigned(null)
                : signatureEnricher.enrich(external);
        return response(status, query, local, external, signature, violations, reasonCode, message);
    }

    ExternalAuditIntegrityResponse noLocalAnchor(ExternalAuditIntegrityQuery query) {
        ExternalAuditSignatureEnricher.Enrichment signature =
                ExternalAuditSignatureEnricher.Enrichment.unsigned(null);
        ExternalSignaturePolicyEvaluator.Result policy = evaluateSignature(signature.verification());
        List<AuditIntegrityViolation> violations = "VALID".equals(policy.status())
                ? List.of()
                : List.of(new AuditIntegrityViolation(policy.reasonCode(), 1, policy.reasonCode()));
        return new ExternalAuditIntegrityResponse(
                policy.status(), 0, query.limit(), query.sourceService(), query.partitionKey(),
                policy.reasonCode(), policy.message(), null, null, sink.immutabilityLevel(),
                durabilityGuarantee(), timestampTrustLevel(), signature.verification().status(),
                null, null, null, signature.verification().reasonCode(), List.of(), List.of(), violations
        );
    }

    ExternalAuditIntegrityResponse response(
            String status,
            ExternalAuditIntegrityQuery query,
            AuditAnchorDocument local,
            ExternalAuditAnchor external,
            ExternalAuditSignatureEnricher.Enrichment signature,
            List<AuditIntegrityViolation> violations,
            String reasonCode,
            String message
    ) {
        return new ExternalAuditIntegrityResponse(
                status, 1, query.limit(), query.sourceService(), query.partitionKey(), reasonCode, message,
                ExternalAuditAnchorSummary.fromLocal(local),
                external == null ? null : ExternalAuditAnchorSummary.fromExternal(
                        external, signature.reference(), sink.immutabilityLevel()),
                sink.immutabilityLevel(), durabilityGuarantee(), timestampTrustLevel(),
                signature.verification().status(), signature.signingKeyId(), signature.signingAlgorithm(),
                signature.signingAuthority(), signature.verification().reasonCode(), List.of(), List.of(), violations
        );
    }

    ExternalAuditIntegrityResponse conflict(
            ExternalAuditIntegrityQuery query,
            ExternalAuditAnchorConflictException exception
    ) {
        return new ExternalAuditIntegrityResponse(
                "CONFLICT", 0, query.limit(), query.sourceService(), query.partitionKey(), "CONFLICT",
                "External anchor witnesses contain incompatible truths.", null, null, sink.immutabilityLevel(),
                durabilityGuarantee(), timestampTrustLevel(), "UNAVAILABLE", null, null, null, "CONFLICT",
                exception.conflictingHashes(), exception.witnessSources(),
                List.of(new AuditIntegrityViolation("EXTERNAL_ANCHOR_CONFLICT", 1, "EXTERNAL_ANCHOR_CONFLICT"))
        );
    }

    ExternalAuditAnchorCoverageResponse coverage(ExternalAnchorCoverageCalculator.Snapshot snapshot) {
        return new ExternalAuditAnchorCoverageResponse(
                "AVAILABLE", snapshot.latestLocalPosition(), snapshot.latestExternalPosition(),
                Math.max(0L, snapshot.latestLocalPosition() - snapshot.latestExternalPosition()),
                snapshot.timeLagSeconds(), snapshot.missingRanges(), snapshot.truncated(), snapshot.limit(),
                snapshot.manifestDegraded() ? ExternalAuditAnchor.REASON_HEAD_MANIFEST_UPDATE_FAILED : null,
                snapshot.manifestDegraded()
                        ? "External anchor coverage is available, but the navigation manifest requires repair."
                        : null
        ).withPublicationStatus(
                snapshot.requiredPublicationFailures(), snapshot.localStatusUnverified(),
                snapshot.recoveredCount(), snapshot.unrecoveredCount()
        );
    }

    ExternalAuditAnchorCoverageResponse unavailableCoverage(
            ExternalAuditIntegrityQuery query,
            String reasonCode,
            String message
    ) {
        return new ExternalAuditAnchorCoverageResponse(
                "UNAVAILABLE", 0, 0, 0, null, List.of(), false, query.limit(), reasonCode, message
        );
    }

    private String timestampTrustLevel() {
        ExternalWitnessCapabilities capabilities = sink.capabilities();
        return capabilities == null ? "APP_OBSERVED" : capabilities.timestampTrustLevel();
    }

    private ExternalDurabilityGuarantee durabilityGuarantee() {
        ExternalWitnessCapabilities capabilities = sink.capabilities();
        return capabilities == null ? ExternalDurabilityGuarantee.NONE : capabilities.durabilityGuarantee();
    }
}
