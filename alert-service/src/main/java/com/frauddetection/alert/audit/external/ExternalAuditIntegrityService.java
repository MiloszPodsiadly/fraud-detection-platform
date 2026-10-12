package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditAnchorRepository;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
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

@Service
public class ExternalAuditIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(ExternalAuditIntegrityService.class);

    private final ExternalAuditIntegrityQueryParser queryParser;
    private final AlertServiceMetrics metrics;
    private final AuditService auditService;
    private final ExternalAnchorIntegrityVerifier verifier;
    private final ExternalAnchorCoverageCalculator coverageCalculator;
    private final ExternalAuditIntegrityResponseFactory responses;

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
        this.queryParser = queryParser;
        this.metrics = metrics;
        this.auditService = auditService;
        AuditTrustAuthorityProperties effectiveTrustProperties = trustAuthorityProperties == null
                ? new AuditTrustAuthorityProperties()
                : trustAuthorityProperties;
        ExternalAuditSignatureEnricher signatureEnricher = new ExternalAuditSignatureEnricher(
                sink,
                publicationStatusRepository,
                trustAuthorityClient,
                metrics
        );
        this.responses = new ExternalAuditIntegrityResponseFactory(
                sink,
                signatureEnricher,
                new ExternalSignaturePolicyEvaluator(effectiveTrustProperties),
                metrics
        );
        this.verifier = new ExternalAnchorIntegrityVerifier(anchorRepository, sink, metrics, responses);
        this.coverageCalculator = new ExternalAnchorCoverageCalculator(
                anchorRepository,
                sink,
                publicationStatusRepository
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
                ExternalAuditIntegrityResponse response = responses.conflict(query, conflictException);
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
        return verifier.verify(query);
    }

    public ExternalAuditAnchorCoverageResponse coverage(String sourceService, Integer limit) {
        return coverage(sourceService, limit, null);
    }

    public ExternalAuditAnchorCoverageResponse coverage(String sourceService, Integer limit, Long fromPosition) {
        ExternalAuditIntegrityQuery query = queryParser.parse(sourceService, limit, fromPosition);
        try {
            return responses.coverage(coverageCalculator.calculate(query));
        } catch (DataAccessException exception) {
            return responses.unavailableCoverage(query, "AUDIT_STORE_UNAVAILABLE", "Local audit anchor store is currently unavailable.");
        } catch (ExternalAuditAnchorSinkException exception) {
            if (exception instanceof ExternalAuditAnchorConflictException) {
                return responses.unavailableCoverage(query, "CONFLICT", "External anchor witnesses contain incompatible truths.");
            }
            return responses.unavailableCoverage(query, externalUnavailableReasonCode(exception), externalUnavailableMessage(externalUnavailableReasonCode(exception)));
        }
    }

    private void auditFailureBestEffort(ExternalAuditIntegrityQuery query, ExternalAuditIntegrityResponse response, String reason) {
        try {
            auditExternalIntegrityRead(query, response, AuditOutcome.FAILED, reason);
        } catch (AuditPersistenceUnavailableException ignored) {
            log.warn("External audit integrity verification access audit could not be persisted.");
        }
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
