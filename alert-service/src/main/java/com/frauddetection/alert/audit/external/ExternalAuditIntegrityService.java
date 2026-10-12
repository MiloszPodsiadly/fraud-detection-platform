package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditAnchorDocument;
import com.frauddetection.alert.audit.AuditAnchorRepository;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
import com.frauddetection.alert.audit.AuditIntegrityViolation;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditPersistenceUnavailableException;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.audit.external.integrity.ExternalAuditIntegrityQuery;
import com.frauddetection.alert.audit.external.integrity.ExternalAuditIntegrityQueryParser;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ExternalAuditIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(ExternalAuditIntegrityService.class);
    private static final String SUPPORTED_SCHEMA_VERSION = "1.0";
    private static final int MAX_MISSING_RANGES = 50;

    private final AuditAnchorRepository anchorRepository;
    private final ExternalAuditAnchorSink sink;
    private final ExternalAuditIntegrityQueryParser queryParser;
    private final AlertServiceMetrics metrics;
    private final AuditService auditService;
    private final ExternalAuditAnchorPublicationStatusRepository publicationStatusRepository;
    private final AuditTrustAuthorityProperties trustAuthorityProperties;
    private final ExternalAuditSignatureEnricher signatureEnricher;

    ExternalAuditIntegrityService(
            AuditAnchorRepository anchorRepository,
            ExternalAuditAnchorSink sink,
            ExternalAuditIntegrityQueryParser queryParser,
            AlertServiceMetrics metrics,
            AuditService auditService
    ) {
        this(anchorRepository, sink, queryParser, metrics, auditService, null, null, new AuditTrustAuthorityProperties());
    }

    @Autowired
    public ExternalAuditIntegrityService(
            AuditAnchorRepository anchorRepository,
            ExternalAuditAnchorSink sink,
            ExternalAuditIntegrityQueryParser queryParser,
            AlertServiceMetrics metrics,
            AuditService auditService,
            ExternalAuditAnchorPublicationStatusRepository publicationStatusRepository,
            AuditTrustAuthorityClient trustAuthorityClient,
            AuditTrustAuthorityProperties trustAuthorityProperties
    ) {
        this.anchorRepository = anchorRepository;
        this.sink = sink;
        this.queryParser = queryParser;
        this.metrics = metrics;
        this.auditService = auditService;
        this.publicationStatusRepository = publicationStatusRepository;
        this.trustAuthorityProperties = trustAuthorityProperties == null ? new AuditTrustAuthorityProperties() : trustAuthorityProperties;
        this.signatureEnricher = new ExternalAuditSignatureEnricher(
                sink,
                publicationStatusRepository,
                trustAuthorityClient,
                metrics
        );
    }

    ExternalAuditIntegrityService(
            AuditAnchorRepository anchorRepository,
            ExternalAuditAnchorSink sink,
            ExternalAuditIntegrityQueryParser queryParser,
            AlertServiceMetrics metrics,
            AuditService auditService,
            ExternalAuditAnchorPublicationStatusRepository publicationStatusRepository,
            AuditTrustAuthorityClient trustAuthorityClient
    ) {
        this(anchorRepository, sink, queryParser, metrics, auditService, publicationStatusRepository, trustAuthorityClient, new AuditTrustAuthorityProperties());
    }

    public ExternalAuditIntegrityResponse verify(String sourceService, Integer limit) {
        ExternalAuditIntegrityQuery query = queryParser.parse(sourceService, limit);
        try {
            ExternalAuditIntegrityResponse response = verify(query);
            metrics.recordExternalIntegrityCheck(response.status());
            auditExternalIntegrityRead(query, response, AuditOutcome.SUCCESS, null);
            return response;
        } catch (DataAccessException exception) {
            ExternalAuditIntegrityResponse response = ExternalAuditIntegrityResponse.unavailable(
                    query,
                    "AUDIT_STORE_UNAVAILABLE",
                    "Local audit anchor store is currently unavailable."
            );
            metrics.recordExternalIntegrityCheck(response.status());
            auditFailureBestEffort(query, response, "AUDIT_STORE_UNAVAILABLE");
            return response;
        } catch (ExternalAuditAnchorSinkException exception) {
            if (exception instanceof ExternalAuditAnchorConflictException conflictException) {
                ExternalAuditIntegrityResponse response = conflict(query, conflictException);
                metrics.recordExternalIntegrityCheck(response.status());
                auditFailureBestEffort(query, response, "CONFLICT");
                return response;
            }
            String reasonCode = externalUnavailableReasonCode(exception);
            ExternalAuditIntegrityResponse response = ExternalAuditIntegrityResponse.unavailable(
                    query,
                    reasonCode,
                    externalUnavailableMessage(reasonCode)
            );
            metrics.recordExternalIntegrityCheck(response.status());
            auditFailureBestEffort(query, response, reasonCode);
            return response;
        }
    }

    private ExternalAuditIntegrityResponse verify(ExternalAuditIntegrityQuery query) {
        AuditAnchorDocument local = anchorRepository.findLatestByPartitionKey(query.partitionKey()).orElse(null);
        if (local == null) {
            return noLocalAnchorResponse(query);
        }

        ExternalAuditAnchor external;
        try {
            java.util.Optional<ExternalAuditAnchor> exactExternal = sink.findByChainPosition(query.partitionKey(), local.chainPosition());
            if (exactExternal == null) {
                exactExternal = java.util.Optional.empty();
            }
            external = exactExternal
                    .or(() -> sink.latest(query.partitionKey()))
                    .orElse(null);
        } catch (ExternalAuditAnchorSinkException exception) {
            if (!isExternalIntegrityMismatch(exception.reason())) {
                throw exception;
            }
            metrics.recordExternalTamperingDetected(exception.reason());
            return response(
                    "INVALID",
                    query,
                    local,
                    null,
                    List.of(new AuditIntegrityViolation(exception.reason(), 1, exception.reason())),
                    exception.reason(),
                    "External anchor binding mismatch."
            );
        }
        if (external == null) {
            return partial(query, local, null, "EXTERNAL_ANCHOR_MISSING", "External anchor is missing for the local chain head.");
        }

        List<AuditIntegrityViolation> violations = new ArrayList<>();
        if (external.chainPosition() < local.chainPosition()) {
            violations.add(new AuditIntegrityViolation("STALE_EXTERNAL_ANCHOR", 1, "STALE_EXTERNAL_ANCHOR"));
            return response("PARTIAL", query, local, external, violations, "STALE_EXTERNAL_ANCHOR", "External anchor is behind the local chain head.");
        }
        if (external.chainPosition() > local.chainPosition()) {
            violations.add(new AuditIntegrityViolation("EXTERNAL_CHAIN_POSITION_AHEAD", 1, "EXTERNAL_CHAIN_POSITION_AHEAD"));
        }
        if (external.chainPosition() == local.chainPosition() && !same(external.localAnchorId(), local.anchorId())) {
            violations.add(new AuditIntegrityViolation("EXTERNAL_LOCAL_ANCHOR_ID_MISMATCH", 1, "EXTERNAL_LOCAL_ANCHOR_ID_MISMATCH"));
        }
        if (!same(external.lastEventHash(), local.lastEventHash())) {
            violations.add(new AuditIntegrityViolation("EXTERNAL_HASH_MISMATCH", 1, "EXTERNAL_HASH_MISMATCH"));
        }
        if (!same(external.hashAlgorithm(), local.hashAlgorithm())) {
            violations.add(new AuditIntegrityViolation("EXTERNAL_HASH_ALGORITHM_MISMATCH", 1, "EXTERNAL_HASH_ALGORITHM_MISMATCH"));
        }
        if (!SUPPORTED_SCHEMA_VERSION.equals(external.schemaVersion())) {
            violations.add(new AuditIntegrityViolation("EXTERNAL_SCHEMA_VERSION_UNSUPPORTED", 1, "EXTERNAL_SCHEMA_VERSION_UNSUPPORTED"));
        }

        boolean integrityViolationsPresent = !violations.isEmpty();
        ExternalAuditSignatureEnricher.Enrichment signature = signatureEnricher.enrich(external);
        boolean manifestDegraded = isManifestDegraded(external) || signature.manifestDegraded();
        if (manifestDegraded) {
            violations.add(new AuditIntegrityViolation(
                    ExternalAuditAnchor.REASON_HEAD_MANIFEST_UPDATE_FAILED,
                    1,
                    ExternalAuditAnchor.REASON_HEAD_MANIFEST_UPDATE_FAILED
            ));
        }

        SignaturePolicyResult signaturePolicy = signaturePolicy(signature.verification());
        metrics.recordAuditSignatureVerification(signature.verification().status());
        metrics.recordAuditSignaturePolicyResult(signaturePolicy.status());
        if (!"VALID".equals(signaturePolicy.status())) {
            violations.add(new AuditIntegrityViolation(
                    signaturePolicy.reasonCode(),
                    1,
                    signaturePolicy.reasonCode()
            ));
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
        return response(status, query, local, external, signature, violations, reasonCode, message);
    }

    public ExternalAuditAnchorCoverageResponse coverage(String sourceService, Integer limit) {
        return coverage(sourceService, limit, null);
    }

    public ExternalAuditAnchorCoverageResponse coverage(String sourceService, Integer limit, Long fromPosition) {
        ExternalAuditIntegrityQuery query = queryParser.parse(sourceService, limit, fromPosition);
        try {
            AuditAnchorDocument latestLocal = anchorRepository.findLatestByPartitionKey(query.partitionKey()).orElse(null);
            long latestLocalPosition = latestLocal == null ? 0L : latestLocal.chainPosition();
            ExternalAuditAnchor latestExternal = sink.latest(query.partitionKey()).orElse(null);
            long latestExternalPosition = latestExternal == null ? 0L : latestExternal.chainPosition();
            boolean observedManifestDegradation = isManifestDegraded(latestExternal);
            long from = query.fromPosition() == null
                    ? (latestLocalPosition == 0L ? 1L : Math.max(1L, latestLocalPosition - query.limit() + 1L))
                    : query.fromPosition();
            long to = latestLocalPosition == 0L ? 0L : Math.min(latestLocalPosition, from + query.limit() - 1L);
            List<ExternalAuditAnchorMissingRange> missingRanges = missingRanges(query.partitionKey(), from, to);
            boolean truncated = latestLocalPosition > to || missingRanges.size() >= MAX_MISSING_RANGES;
            Long timeLagSeconds = latestLocal != null && latestExternal != null
                    ? Math.max(0L, java.time.Duration.between(latestExternal.createdAt(), latestLocal.createdAt()).toSeconds())
                    : null;
            PublicationCoverage publicationCoverage = publicationCoverage(query.partitionKey(), query.limit());
            boolean manifestDegraded = observedManifestDegradation || publicationCoverage.manifestDegradedCount() > 0;
            int unrecoveredCount = publicationCoverage.unrecoveredCount()
                    + (observedManifestDegradation && publicationCoverage.manifestDegradedCount() == 0 ? 1 : 0);
            return new ExternalAuditAnchorCoverageResponse(
                    "AVAILABLE",
                    latestLocalPosition,
                    latestExternalPosition,
                    Math.max(0L, latestLocalPosition - latestExternalPosition),
                    timeLagSeconds,
                    missingRanges,
                    truncated,
                    query.limit(),
                    manifestDegraded ? ExternalAuditAnchor.REASON_HEAD_MANIFEST_UPDATE_FAILED : null,
                    manifestDegraded
                            ? "External anchor coverage is available, but the navigation manifest requires repair."
                            : null
            ).withPublicationStatus(
                    publicationCoverage.requiredPublicationFailures(),
                    publicationCoverage.localStatusUnverified(),
                    publicationCoverage.recoveredCount(),
                    unrecoveredCount
            );
        } catch (DataAccessException exception) {
            return unavailableCoverage(query, "AUDIT_STORE_UNAVAILABLE", "Local audit anchor store is currently unavailable.");
        } catch (ExternalAuditAnchorSinkException exception) {
            if (exception instanceof ExternalAuditAnchorConflictException) {
                return unavailableCoverage(query, "CONFLICT", "External anchor witnesses contain incompatible truths.");
            }
            return unavailableCoverage(query, externalUnavailableReasonCode(exception), externalUnavailableMessage(externalUnavailableReasonCode(exception)));
        }
    }

    private List<ExternalAuditAnchorMissingRange> missingRanges(String partitionKey, long from, long to) {
        if (to < from || publicationStatusRepository == null) {
            return List.of();
        }
        List<AuditAnchorDocument> anchors = anchorRepository.findByPartitionKeyAndChainPositionBetween(partitionKey, from, to, (int) Math.min(100, to - from + 1));
        java.util.Map<Long, AuditAnchorDocument> anchorsByPosition = anchors.stream()
                .collect(java.util.stream.Collectors.toMap(AuditAnchorDocument::chainPosition, java.util.function.Function.identity(), (left, right) -> left));
        java.util.Map<String, ExternalAuditAnchorPublicationStatusDocument> statusesByAnchorId = publicationStatusRepository.findByLocalAnchorIds(
                        anchors.stream().map(AuditAnchorDocument::anchorId).toList()
                ).stream()
                .collect(java.util.stream.Collectors.toMap(ExternalAuditAnchorPublicationStatusDocument::localAnchorId, java.util.function.Function.identity(), (left, right) -> left));
        List<ExternalAuditAnchorMissingRange> ranges = new ArrayList<>();
        Long openStart = null;
        for (long position = from; position <= to; position++) {
            AuditAnchorDocument anchor = anchorsByPosition.get(position);
            ExternalAuditAnchorPublicationStatusDocument status = anchor == null ? null : statusesByAnchorId.get(anchor.anchorId());
            boolean present = status != null && ExternalAuditAnchor.STATUS_PUBLISHED.equals(status.externalPublicationStatus());
            if (!present && openStart == null) {
                openStart = position;
            }
            if ((present || position == to) && openStart != null) {
                long end = present ? position - 1 : position;
                ranges.add(new ExternalAuditAnchorMissingRange(openStart, end));
                openStart = null;
                if (ranges.size() >= MAX_MISSING_RANGES) {
                    return List.copyOf(ranges);
                }
            }
        }
        return List.copyOf(ranges);
    }

    private PublicationCoverage publicationCoverage(String partitionKey, int limit) {
        if (publicationStatusRepository == null) {
            return new PublicationCoverage(0, 0, 0, 0, 0);
        }
        int requiredFailures = 0;
        int localStatusUnverified = 0;
        int recovered = 0;
        int unrecovered = 0;
        int manifestDegraded = 0;
        List<AuditAnchorDocument> anchors = anchorRepository.findHeadWindow(partitionKey, limit);
        if (anchors == null) {
            anchors = List.of();
        }
        for (AuditAnchorDocument anchor : anchors) {
            ExternalAuditAnchorPublicationStatusDocument status = publicationStatusRepository.findByLocalAnchorId(anchor.anchorId()).orElse(null);
            if (status == null) {
                localStatusUnverified++;
                unrecovered++;
                continue;
            }
            if (isManifestDegraded(status)) {
                manifestDegraded++;
                unrecovered++;
            } else if (ExternalAuditAnchor.STATUS_LOCAL_ANCHOR_CREATED_EXTERNAL_REQUIRED_FAILED.equals(status.externalPublicationStatus())
                    || Boolean.TRUE.equals(status.externalRequired())) {
                requiredFailures++;
                unrecovered++;
            } else if (ExternalAuditAnchor.STATUS_LOCAL_STATUS_UNVERIFIED.equals(status.externalPublicationStatus())) {
                localStatusUnverified++;
                unrecovered++;
            } else if ("RECOVERED".equals(status.localTrackingStatus())) {
                recovered++;
            } else if (ExternalAuditAnchor.STATUS_MISSING.equals(status.externalPublicationStatus())
                    || ExternalAuditAnchor.STATUS_INVALID.equals(status.externalPublicationStatus())
                    || ExternalAuditAnchor.STATUS_FAILED.equals(status.externalPublicationStatus())) {
                unrecovered++;
            }
        }
        return new PublicationCoverage(requiredFailures, localStatusUnverified, recovered, unrecovered, manifestDegraded);
    }

    private boolean isManifestDegraded(ExternalAuditAnchor anchor) {
        return anchor != null
                && ExternalAuditAnchor.MANIFEST_STATUS_FAILED.equals(anchor.manifestStatus());
    }

    private boolean isManifestDegraded(ExternalAuditAnchorPublicationStatusDocument status) {
        return status != null
                && (ExternalAuditAnchor.MANIFEST_STATUS_FAILED.equals(status.headManifestStatus())
                || ExternalAuditAnchor.MANIFEST_STATUS_FAILED.equals(status.manifestStatus()));
    }

    private ExternalAuditAnchorCoverageResponse unavailableCoverage(
            ExternalAuditIntegrityQuery query,
            String reasonCode,
            String message
    ) {
        return new ExternalAuditAnchorCoverageResponse(
                "UNAVAILABLE",
                0,
                0,
                0,
                null,
                List.of(),
                false,
                query.limit(),
                reasonCode,
                message
        );
    }

    private ExternalAuditIntegrityResponse partial(
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

    private ExternalAuditIntegrityResponse response(
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
        return new ExternalAuditIntegrityResponse(
                status,
                1,
                query.limit(),
                query.sourceService(),
                query.partitionKey(),
                reasonCode,
                message,
                ExternalAuditAnchorSummary.fromLocal(local),
                external == null ? null : ExternalAuditAnchorSummary.fromExternal(
                        external,
                        signature.reference(),
                        sink.immutabilityLevel()
                ),
                sink.immutabilityLevel(),
                durabilityGuarantee(),
                timestampTrustLevel(),
                signature.verification().status(),
                signature.signingKeyId(),
                signature.signingAlgorithm(),
                signature.signingAuthority(),
                signature.verification().reasonCode(),
                List.of(),
                List.of(),
                violations
        );
    }

    private ExternalAuditIntegrityResponse noLocalAnchorResponse(ExternalAuditIntegrityQuery query) {
        ExternalAuditSignatureEnricher.Enrichment signature =
                ExternalAuditSignatureEnricher.Enrichment.unsigned(null);
        SignaturePolicyResult signaturePolicy = signaturePolicy(signature.verification());
        metrics.recordAuditSignatureVerification(signature.verification().status());
        metrics.recordAuditSignaturePolicyResult(signaturePolicy.status());
        List<AuditIntegrityViolation> violations = "VALID".equals(signaturePolicy.status())
                ? List.of()
                : List.of(new AuditIntegrityViolation(signaturePolicy.reasonCode(), 1, signaturePolicy.reasonCode()));
        return new ExternalAuditIntegrityResponse(
                signaturePolicy.status(),
                0,
                query.limit(),
                query.sourceService(),
                query.partitionKey(),
                signaturePolicy.reasonCode(),
                signaturePolicy.message(),
                null,
                null,
                sink.immutabilityLevel(),
                durabilityGuarantee(),
                timestampTrustLevel(),
                signature.verification().status(),
                null,
                null,
                null,
                signature.verification().reasonCode(),
                List.of(),
                List.of(),
                violations
        );
    }

    private ExternalAuditIntegrityResponse response(
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
                status,
                1,
                query.limit(),
                query.sourceService(),
                query.partitionKey(),
                reasonCode,
                message,
                ExternalAuditAnchorSummary.fromLocal(local),
                external == null ? null : ExternalAuditAnchorSummary.fromExternal(
                        external,
                        signature.reference(),
                        sink.immutabilityLevel()
                ),
                sink.immutabilityLevel(),
                durabilityGuarantee(),
                timestampTrustLevel(),
                signature.verification().status(),
                signature.signingKeyId(),
                signature.signingAlgorithm(),
                signature.signingAuthority(),
                signature.verification().reasonCode(),
                List.of(),
                List.of(),
                violations
        );
    }

    private ExternalAuditIntegrityResponse conflict(
            ExternalAuditIntegrityQuery query,
            ExternalAuditAnchorConflictException exception
    ) {
        return new ExternalAuditIntegrityResponse(
                "CONFLICT",
                0,
                query.limit(),
                query.sourceService(),
                query.partitionKey(),
                "CONFLICT",
                "External anchor witnesses contain incompatible truths.",
                null,
                null,
                sink.immutabilityLevel(),
                durabilityGuarantee(),
                timestampTrustLevel(),
                "UNAVAILABLE",
                null,
                null,
                null,
                "CONFLICT",
                exception.conflictingHashes(),
                exception.witnessSources(),
                List.of(new AuditIntegrityViolation("EXTERNAL_ANCHOR_CONFLICT", 1, "EXTERNAL_ANCHOR_CONFLICT"))
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

    private SignaturePolicyResult signaturePolicy(AuditTrustSignatureVerificationResult verification) {
        String verificationStatus = verification == null ? "UNAVAILABLE" : verification.status();
        if (!trustAuthorityProperties.isEnabled()) {
            if ("INVALID".equals(verificationStatus)
                    || "UNKNOWN_KEY".equals(verificationStatus)
                    || "KEY_REVOKED".equals(verificationStatus)) {
                return new SignaturePolicyResult("INVALID", "SIGNATURE_" + verificationStatus, "Signature verification failed.");
            }
            return SignaturePolicyResult.valid();
        }
        boolean required = trustAuthorityProperties.isSigningRequired();
        return switch (verificationStatus) {
            case "VALID" -> SignaturePolicyResult.valid();
            case "UNSIGNED" -> new SignaturePolicyResult(
                    required ? "INVALID" : "PARTIAL",
                    required ? "SIGNATURE_UNSIGNED_REQUIRED" : "SIGNATURE_UNSIGNED",
                    "External anchor signature is missing."
            );
            case "UNAVAILABLE" -> new SignaturePolicyResult(
                    required ? "INVALID" : "PARTIAL",
                    required ? "SIGNATURE_UNAVAILABLE_REQUIRED" : "SIGNATURE_UNAVAILABLE",
                    "Trust authority signature verification is unavailable."
            );
            case "UNKNOWN_KEY", "KEY_REVOKED", "INVALID" -> new SignaturePolicyResult(
                    "INVALID",
                    "SIGNATURE_" + verificationStatus,
                    "Signature verification failed."
            );
            default -> new SignaturePolicyResult("INVALID", "SIGNATURE_INVALID", "Signature verification failed.");
        };
    }

    private record SignaturePolicyResult(String status, String reasonCode, String message) {
        static SignaturePolicyResult valid() {
            return new SignaturePolicyResult("VALID", null, null);
        }
    }

    private void auditFailureBestEffort(ExternalAuditIntegrityQuery query, ExternalAuditIntegrityResponse response, String reason) {
        try {
            auditExternalIntegrityRead(query, response, AuditOutcome.FAILED, reason);
        } catch (AuditPersistenceUnavailableException ignored) {
            log.warn("External audit integrity verification access audit could not be persisted.");
        }
    }

    private record PublicationCoverage(
            int requiredPublicationFailures,
            int localStatusUnverified,
            int recoveredCount,
            int unrecoveredCount,
            int manifestDegradedCount
    ) {
    }

    private void auditExternalIntegrityRead(
            ExternalAuditIntegrityQuery query,
            ExternalAuditIntegrityResponse response,
            AuditOutcome outcome,
            String failureReason
    ) {
        auditService.audit(
                AuditAction.VERIFY_EXTERNAL_AUDIT_INTEGRITY,
                AuditResourceType.AUDIT_EXTERNAL_INTEGRITY,
                null,
                null,
                "external-audit-integrity-reader",
                outcome,
                failureReason,
                AuditEventMetadataSummary.auditRead(
                        null,
                        "alert-service",
                        "1.0",
                        "GET /api/v1/audit/integrity/external",
                        "source_service=" + query.sourceService() + ";limit=" + query.limit(),
                        response.checked()
                )
        );
    }

    private boolean same(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private boolean isExternalIntegrityMismatch(String reason) {
        return "EXTERNAL_OBJECT_KEY_MISMATCH".equals(reason)
                || "EXTERNAL_PAYLOAD_HASH_MISMATCH".equals(reason)
                || "EXTERNAL_ANCHOR_ID_MISMATCH".equals(reason)
                || "EXTERNAL_ANCHOR_ID_VERSION_UNSUPPORTED".equals(reason)
                || "MISMATCH".equals(reason);
    }

    private String externalUnavailableReasonCode(ExternalAuditAnchorSinkException exception) {
        return switch (exception.reason()) {
            case "HEAD_SCAN_PAGINATION_UNSUPPORTED", "HEAD_SCAN_LIMIT_EXCEEDED" -> exception.reason();
            default -> "EXTERNAL_ANCHOR_STORE_UNAVAILABLE";
        };
    }

    private String externalUnavailableMessage(String reasonCode) {
        return switch (reasonCode) {
            case "HEAD_SCAN_PAGINATION_UNSUPPORTED", "HEAD_SCAN_LIMIT_EXCEEDED" ->
                    "External audit anchor head cannot be proven from the object-store listing.";
            default -> "External audit anchor sink is currently unavailable.";
        };
    }
}
