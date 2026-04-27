package com.frauddetection.alert.audit;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

@Service
public class AuditIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(AuditIntegrityService.class);

    private static final String SUPPORTED_SCHEMA_VERSION = "1.0";
    private static final String SUPPORTED_HASH_ALGORITHM = "SHA-256";
    private static final String DEFAULT_SOURCE_SERVICE = "alert-service";
    static final Duration MAX_VERIFICATION_DURATION = Duration.ofSeconds(2);
    private static final String TIME_BUDGET_EXCEEDED = "INTEGRITY_VERIFICATION_TIME_BUDGET_EXCEEDED";

    private final AuditEventRepository repository;
    private final AuditAnchorRepository anchorRepository;
    private final AuditIntegrityQueryParser queryParser;
    private final AlertServiceMetrics metrics;
    private final AuditService auditService;
    private final LongSupplier nanoTime;
    private final long maxVerificationNanos;

    @Autowired
    public AuditIntegrityService(
            AuditEventRepository repository,
            AuditAnchorRepository anchorRepository,
            AuditIntegrityQueryParser queryParser,
            AlertServiceMetrics metrics,
            AuditService auditService
    ) {
        this(repository, anchorRepository, queryParser, metrics, auditService, System::nanoTime, MAX_VERIFICATION_DURATION.toNanos());
    }

    AuditIntegrityService(
            AuditEventRepository repository,
            AuditAnchorRepository anchorRepository,
            AuditIntegrityQueryParser queryParser,
            AlertServiceMetrics metrics,
            AuditService auditService,
            LongSupplier nanoTime,
            long maxVerificationNanos
    ) {
        this.repository = repository;
        this.anchorRepository = anchorRepository;
        this.queryParser = queryParser;
        this.metrics = metrics;
        this.auditService = auditService;
        this.nanoTime = nanoTime;
        this.maxVerificationNanos = maxVerificationNanos;
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
        Set<Long> seenChainPositions = new HashSet<>();
        AuditEventDocument previous = null;
        int checked = 0;
        boolean timeBudgetExceeded = false;
        long startedAt = nanoTime.getAsLong();
        for (int i = 0; i < documents.size(); i++) {
            if (timeBudgetExceeded(startedAt)) {
                timeBudgetExceeded = true;
                break;
            }
            AuditEventDocument current = documents.get(i);
            int position = i + 1;
            checked++;
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
            validateChainPosition(current, previous, query, position, seenChainPositions, violations);
            previous = current;
        }

        String partitionKey = partitionKey(query, documents);
        List<AuditEventDocument> checkedDocuments = documents.subList(0, checked);
        boolean externalPredecessor = firstEventHasExternalPredecessor(checkedDocuments, partitionKey);
        if (query.mode() == AuditIntegrityVerificationMode.FULL_CHAIN
                && !checkedDocuments.isEmpty()
                && checkedDocuments.getFirst().previousEventHash() != null) {
            violations.add(new AuditIntegrityViolation("MISSING_PREDECESSOR", 1, "MISSING_PREDECESSOR"));
        }
        String lastEventHash = checkedDocuments.isEmpty() ? null : checkedDocuments.getLast().eventHash();
        String lastAnchorHash = null;
        Long chainCount = null;
        if (partitionKey != null) {
            AuditEventDocument chainHead = repository.findLatestByPartitionKey(partitionKey).orElse(null);
            AuditAnchorDocument anchor = anchorRepository.findLatestByPartitionKey(partitionKey).orElse(null);
            if (chainHead != null && anchor == null) {
                violations.add(new AuditIntegrityViolation("ANCHOR_MISSING", violationPosition(checkedDocuments), "ANCHOR_MISSING"));
            } else if (chainHead != null) {
                lastAnchorHash = anchor.lastEventHash();
                if (!safeEquals(chainHead.eventHash(), anchor.lastEventHash())) {
                    violations.add(new AuditIntegrityViolation("ANCHOR_HASH_MISMATCH", violationPosition(checkedDocuments), "ANCHOR_HASH_MISMATCH"));
                }
                chainCount = repository.countByPartitionKey(partitionKey);
                if (chainCount != anchor.chainPosition()) {
                    violations.add(new AuditIntegrityViolation("ANCHOR_CHAIN_POSITION_MISMATCH", violationPosition(checkedDocuments), "ANCHOR_CHAIN_POSITION_MISMATCH"));
                }
            }
        }

        String status = violations.isEmpty() ? "VALID" : "INVALID";
        if (violations.isEmpty() && (timeBudgetExceeded || partialByLimit(query, documents, checked, chainCount))) {
            status = "PARTIAL";
        }
        return new AuditIntegrityResponse(
                status,
                checked,
                query.limit(),
                query.mode().name(),
                partialWindow(query, externalPredecessor),
                externalPredecessor,
                externalPredecessor,
                timeBudgetExceeded ? TIME_BUDGET_EXCEEDED : null,
                timeBudgetExceeded ? "Audit integrity verification stopped after reaching its time budget." : null,
                checkedDocuments.isEmpty() ? null : checkedDocuments.getFirst().eventHash(),
                lastEventHash,
                partitionKey,
                lastAnchorHash,
                violations
        );
    }

    private void validateChainPosition(
            AuditEventDocument current,
            AuditEventDocument previous,
            AuditIntegrityQuery query,
            int position,
            Set<Long> seenChainPositions,
            List<AuditIntegrityViolation> violations
    ) {
        Long currentPosition = current.chainPosition();
        if (currentPosition == null || currentPosition <= 0) {
            violations.add(new AuditIntegrityViolation("CHAIN_POSITION_INVALID", position, "CHAIN_POSITION_INVALID"));
            return;
        }
        if (!seenChainPositions.add(currentPosition)) {
            violations.add(new AuditIntegrityViolation("CHAIN_POSITION_DUPLICATE", position, "CHAIN_POSITION_DUPLICATE"));
        }
        if (previous != null && previous.chainPosition() != null && currentPosition != previous.chainPosition() + 1L) {
            violations.add(new AuditIntegrityViolation("CHAIN_POSITION_GAP", position, "CHAIN_POSITION_GAP"));
        }
        if (previous == null
                && query.mode() == AuditIntegrityVerificationMode.FULL_CHAIN
                && current.previousEventHash() == null
                && currentPosition != 1L) {
            violations.add(new AuditIntegrityViolation("CHAIN_POSITION_GAP", position, "CHAIN_POSITION_GAP"));
        }
    }

    private boolean timeBudgetExceeded(long startedAt) {
        return nanoTime.getAsLong() - startedAt > maxVerificationNanos;
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

    private boolean partialByLimit(
            AuditIntegrityQuery query,
            List<AuditEventDocument> documents,
            int checked,
            Long chainCount
    ) {
        if (documents.size() != query.limit()) {
            return false;
        }
        if (query.mode() != AuditIntegrityVerificationMode.FULL_CHAIN) {
            return true;
        }
        return chainCount == null || chainCount > checked;
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
