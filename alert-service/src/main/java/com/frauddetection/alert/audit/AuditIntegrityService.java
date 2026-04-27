package com.frauddetection.alert.audit;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class AuditIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(AuditIntegrityService.class);

    private static final String SUPPORTED_SCHEMA_VERSION = "1.0";
    private static final String SUPPORTED_HASH_ALGORITHM = "SHA-256";
    private static final String DEFAULT_SOURCE_SERVICE = "alert-service";

    private final AuditEventRepository repository;
    private final AuditAnchorRepository anchorRepository;
    private final AuditIntegrityQueryParser queryParser;
    private final AlertServiceMetrics metrics;
    private final AuditService auditService;

    public AuditIntegrityService(
            AuditEventRepository repository,
            AuditAnchorRepository anchorRepository,
            AuditIntegrityQueryParser queryParser,
            AlertServiceMetrics metrics,
            AuditService auditService
    ) {
        this.repository = repository;
        this.anchorRepository = anchorRepository;
        this.queryParser = queryParser;
        this.metrics = metrics;
        this.auditService = auditService;
    }

    public AuditIntegrityResponse verify(String from, String to, String sourceService, Integer limit) {
        AuditIntegrityQuery query = queryParser.parse(from, to, sourceService, null, limit);
        return verify(query, true);
    }

    public AuditIntegrityResponse verify(String from, String to, String sourceService, String mode, Integer limit) {
        AuditIntegrityQuery query = queryParser.parse(from, to, sourceService, mode, limit);
        return verify(query, true);
    }

    AuditIntegrityResponse verifyScheduled(String sourceService, int limit) {
        AuditIntegrityQuery query = queryParser.parse(null, null, sourceService, AuditIntegrityVerificationMode.HEAD.name(), limit);
        return verify(query, false);
    }

    private AuditIntegrityResponse verify(AuditIntegrityQuery query, boolean auditRead) {
        try {
            List<AuditEventDocument> documents = documents(query);
            AuditIntegrityResponse response = verifyDocuments(documents, query);
            metrics.recordAuditIntegrityCheck(response.status());
            metrics.recordForensicAuditIntegrityCheck(response.status());
            metrics.recordAuditIntegritySnapshot(response.status(), response.lastEventHash(), response.lastAnchorHash());
            response.violations().forEach(violation -> metrics.recordAuditIntegrityViolation(violation.violationType()));
            response.violations().forEach(violation -> metrics.recordForensicAuditIntegrityViolation(violation.violationType()));
            if (auditRead) {
                auditIntegrityRead(query, response, AuditOutcome.SUCCESS, null);
            }
            return response;
        } catch (DataAccessException exception) {
            metrics.recordAuditIntegrityCheck("UNAVAILABLE");
            metrics.recordForensicAuditIntegrityCheck("UNAVAILABLE");
            metrics.recordAuditIntegritySnapshot("UNAVAILABLE", null, null);
            AuditIntegrityResponse response = AuditIntegrityResponse.unavailable(query.limit());
            if (auditRead) {
                try {
                    auditIntegrityRead(query, response, AuditOutcome.FAILED, "AUDIT_STORE_UNAVAILABLE");
                } catch (AuditPersistenceUnavailableException ignored) {
                    log.warn("Audit read access audit could not be persisted for audit integrity verification.");
                }
            }
            return response;
        }
    }

    private List<AuditEventDocument> documents(AuditIntegrityQuery query) {
        String partitionKey = partitionKey(query);
        return switch (query.mode()) {
            case HEAD -> repository.findHeadWindow(partitionKey, query.limit());
            case FULL_CHAIN -> repository.findFullChain(partitionKey, query.limit());
            case WINDOW -> repository.findIntegrityWindow(
                    query.sourceService() == null ? DEFAULT_SOURCE_SERVICE : query.sourceService(),
                    query.from(),
                    query.to(),
                    query.limit()
            );
        };
    }

    private AuditIntegrityResponse verifyDocuments(List<AuditEventDocument> documents, AuditIntegrityQuery query) {
        List<AuditIntegrityViolation> violations = new ArrayList<>();
        Set<String> seenPreviousHashes = new HashSet<>();
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
            if (current.previousEventHash() != null && !seenPreviousHashes.add(current.previousEventHash())) {
                violations.add(new AuditIntegrityViolation("CHAIN_FORK_DETECTED", position, "CHAIN_FORK_DETECTED"));
            }
            previous = current;
        }

        String partitionKey = partitionKey(query, documents);
        boolean externalPredecessor = firstEventHasExternalPredecessor(documents, partitionKey);
        if (query.mode() == AuditIntegrityVerificationMode.FULL_CHAIN
                && !documents.isEmpty()
                && documents.getFirst().previousEventHash() != null) {
            violations.add(new AuditIntegrityViolation("MISSING_PREDECESSOR", 1, "MISSING_PREDECESSOR"));
        }
        String lastEventHash = documents.isEmpty() ? null : documents.getLast().eventHash();
        String lastAnchorHash = null;
        if (partitionKey != null) {
            AuditEventDocument chainHead = repository.findLatestByPartitionKey(partitionKey).orElse(null);
            AuditAnchorDocument anchor = anchorRepository.findLatestByPartitionKey(partitionKey).orElse(null);
            if (chainHead != null && anchor == null) {
                violations.add(new AuditIntegrityViolation("ANCHOR_MISSING", violationPosition(documents), "ANCHOR_MISSING"));
            } else if (chainHead != null) {
                lastAnchorHash = anchor.lastEventHash();
                if (!safeEquals(chainHead.eventHash(), anchor.lastEventHash())) {
                    violations.add(new AuditIntegrityViolation("ANCHOR_HASH_MISMATCH", violationPosition(documents), "ANCHOR_HASH_MISMATCH"));
                }
                if (repository.countByPartitionKey(partitionKey) != anchor.chainPosition()) {
                    violations.add(new AuditIntegrityViolation("ANCHOR_CHAIN_POSITION_MISMATCH", violationPosition(documents), "ANCHOR_CHAIN_POSITION_MISMATCH"));
                }
            }
        }

        String status = violations.isEmpty() ? "VALID" : "INVALID";
        if (violations.isEmpty() && documents.size() == query.limit()) {
            status = "PARTIAL";
        }
        return new AuditIntegrityResponse(
                status,
                documents.size(),
                query.limit(),
                query.mode().name(),
                partialWindow(query, externalPredecessor),
                externalPredecessor,
                externalPredecessor,
                null,
                null,
                documents.isEmpty() ? null : documents.getFirst().eventHash(),
                lastEventHash,
                partitionKey,
                lastAnchorHash,
                violations
        );
    }

    private String partitionKey(AuditIntegrityQuery query) {
        return AuditIntegrityQueryParser.partitionKey(query.sourceService() == null ? DEFAULT_SOURCE_SERVICE : query.sourceService());
    }

    private String partitionKey(AuditIntegrityQuery query, List<AuditEventDocument> documents) {
        if (query.sourceService() != null) {
            return AuditIntegrityQueryParser.partitionKey(query.sourceService());
        }
        if (documents.isEmpty()) {
            return AuditEventDocument.PARTITION_KEY;
        }
        return documents.getFirst().partitionKey();
    }

    private boolean firstEventHasExternalPredecessor(List<AuditEventDocument> documents, String partitionKey) {
        if (documents.isEmpty() || documents.getFirst().previousEventHash() == null) {
            return false;
        }
        return repository.findByPartitionKeyAndEventHash(partitionKey, documents.getFirst().previousEventHash()).isPresent();
    }

    private boolean partialWindow(AuditIntegrityQuery query, boolean externalPredecessor) {
        return query.mode() != AuditIntegrityVerificationMode.FULL_CHAIN && externalPredecessor;
    }

    private int violationPosition(List<AuditEventDocument> documents) {
        return Math.max(1, documents.size());
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
