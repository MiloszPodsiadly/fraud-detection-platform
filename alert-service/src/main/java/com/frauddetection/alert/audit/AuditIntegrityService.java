package com.frauddetection.alert.audit;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class AuditIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(AuditIntegrityService.class);

    private static final String SUPPORTED_SCHEMA_VERSION = "1.0";
    private static final String SUPPORTED_HASH_ALGORITHM = "SHA-256";

    private final AuditEventRepository repository;
    private final AuditIntegrityQueryParser queryParser;
    private final AlertServiceMetrics metrics;
    private final AuditService auditService;

    public AuditIntegrityService(
            AuditEventRepository repository,
            AuditIntegrityQueryParser queryParser,
            AlertServiceMetrics metrics,
            AuditService auditService
    ) {
        this.repository = repository;
        this.queryParser = queryParser;
        this.metrics = metrics;
        this.auditService = auditService;
    }

    public AuditIntegrityResponse verify(String from, String to, String sourceService, Integer limit) {
        AuditIntegrityQuery query = queryParser.parse(from, to, sourceService, limit);
        try {
            List<AuditEventDocument> documents = repository.findIntegrityWindow(
                    query.sourceService(),
                    query.from(),
                    query.to(),
                    query.limit()
            );
            AuditIntegrityResponse response = verifyDocuments(documents, query.limit());
            metrics.recordAuditIntegrityCheck(response.status());
            response.violations().forEach(violation -> metrics.recordAuditIntegrityViolation(violation.violationType()));
            auditIntegrityRead(query, response, AuditOutcome.SUCCESS, null);
            return response;
        } catch (DataAccessException exception) {
            metrics.recordAuditIntegrityCheck("UNAVAILABLE");
            AuditIntegrityResponse response = AuditIntegrityResponse.unavailable(query.limit());
            try {
                auditIntegrityRead(query, response, AuditOutcome.FAILED, "AUDIT_STORE_UNAVAILABLE");
            } catch (AuditPersistenceUnavailableException ignored) {
                log.warn("Audit read access audit could not be persisted for audit integrity verification.");
            }
            return response;
        }
    }

    private AuditIntegrityResponse verifyDocuments(List<AuditEventDocument> documents, int limit) {
        List<AuditIntegrityViolation> violations = new ArrayList<>();
        AuditEventDocument previous = null;
        for (int i = 0; i < documents.size(); i++) {
            AuditEventDocument current = documents.get(i);
            int position = i + 1;
            if (!SUPPORTED_SCHEMA_VERSION.equals(current.schemaVersion())) {
                violations.add(new AuditIntegrityViolation("INVALID_SCHEMA_VERSION", position, "UNSUPPORTED_SCHEMA_VERSION"));
            }
            if (!SUPPORTED_HASH_ALGORITHM.equals(current.hashAlgorithm())) {
                violations.add(new AuditIntegrityViolation("UNSUPPORTED_HASH_ALGORITHM", position, "UNSUPPORTED_HASH_ALGORITHM"));
            } else if (!AuditEventHasher.matches(current)) {
                violations.add(new AuditIntegrityViolation("EVENT_HASH_MISMATCH", position, "EVENT_HASH_MISMATCH"));
            }
            if (previous != null && !safeEquals(previous.eventHash(), current.previousEventHash())) {
                violations.add(new AuditIntegrityViolation("PREVIOUS_HASH_MISMATCH", position, "PREVIOUS_HASH_MISMATCH"));
            }
            previous = current;
        }

        String status = violations.isEmpty() ? "VALID" : "INVALID";
        if (violations.isEmpty() && documents.size() == limit) {
            status = "PARTIAL";
        }
        return new AuditIntegrityResponse(
                status,
                documents.size(),
                limit,
                null,
                null,
                documents.isEmpty() ? null : documents.getFirst().eventHash(),
                documents.isEmpty() ? null : documents.getLast().eventHash(),
                violations
        );
    }

    private void auditIntegrityRead(
            AuditIntegrityQuery query,
            AuditIntegrityResponse response,
            AuditOutcome outcome,
            String failureReason
    ) {
        auditService.audit(
                AuditAction.VERIFY_AUDIT_INTEGRITY,
                AuditResourceType.AUDIT_INTEGRITY,
                null,
                null,
                "audit-integrity-reader",
                outcome,
                failureReason,
                AuditEventMetadataSummary.auditRead(
                        null,
                        "alert-service",
                        "1.0",
                        "GET /api/v1/audit/integrity",
                        filtersSummary(query.sourceService(), query.limit()),
                        response.checked()
                )
        );
    }

    private String filtersSummary(String sourceService, int limit) {
        String source = sourceService == null ? "all" : sourceService;
        return "source_service=" + source + ";limit=" + limit;
    }

    private boolean safeEquals(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }
}
