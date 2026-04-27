package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditAnchorDocument;
import com.frauddetection.alert.audit.AuditAnchorRepository;
import com.frauddetection.alert.audit.AuditEventDocument;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
import com.frauddetection.alert.audit.AuditEventRepository;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditPersistenceUnavailableException;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AuditEvidenceExportService {

    private static final Logger log = LoggerFactory.getLogger(AuditEvidenceExportService.class);

    private final AuditEventRepository eventRepository;
    private final AuditAnchorRepository anchorRepository;
    private final ExternalAuditAnchorSink sink;
    private final AuditEvidenceExportQueryParser queryParser;
    private final AlertServiceMetrics metrics;
    private final AuditService auditService;

    public AuditEvidenceExportService(
            AuditEventRepository eventRepository,
            AuditAnchorRepository anchorRepository,
            ExternalAuditAnchorSink sink,
            AuditEvidenceExportQueryParser queryParser,
            AlertServiceMetrics metrics,
            AuditService auditService
    ) {
        this.eventRepository = eventRepository;
        this.anchorRepository = anchorRepository;
        this.sink = sink;
        this.queryParser = queryParser;
        this.metrics = metrics;
        this.auditService = auditService;
    }

    public AuditEvidenceExportResponse export(String from, String to, String sourceService, Integer limit) {
        AuditEvidenceExportQuery query = queryParser.parse(from, to, sourceService, limit);
        try {
            List<AuditEventDocument> documents = eventRepository.findEvidenceWindow(
                    query.sourceService(),
                    query.from(),
                    query.to(),
                    query.limit()
            );
            AuditEvidenceExportResponse response = available(query, documents);
            metrics.recordEvidenceExport("AVAILABLE");
            auditEvidenceExport(query, response.count(), AuditOutcome.SUCCESS, null);
            return response;
        } catch (DataAccessException exception) {
            AuditEvidenceExportResponse response = AuditEvidenceExportResponse.unavailable(query);
            metrics.recordEvidenceExport("UNAVAILABLE");
            try {
                auditEvidenceExport(query, response.count(), AuditOutcome.FAILED, "AUDIT_STORE_UNAVAILABLE");
            } catch (AuditPersistenceUnavailableException ignored) {
                log.warn("Audit evidence export access audit could not be persisted.");
            }
            return response;
        }
    }

    private AuditEvidenceExportResponse available(AuditEvidenceExportQuery query, List<AuditEventDocument> documents) {
        if (documents.isEmpty()) {
            return new AuditEvidenceExportResponse("AVAILABLE", 0, query.limit(), query.sourceService(), query.from(), query.to(), null, null, List.of());
        }
        long minPosition = documents.stream()
                .map(AuditEventDocument::chainPosition)
                .filter(position -> position != null && position > 0)
                .mapToLong(Long::longValue)
                .min()
                .orElse(0L);
        long maxPosition = documents.stream()
                .map(AuditEventDocument::chainPosition)
                .filter(position -> position != null && position > 0)
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L);
        Map<Long, AuditAnchorDocument> localAnchors = localAnchors(query, minPosition, maxPosition);
        Map<Long, ExternalAuditAnchor> externalAnchors = externalAnchors(query);
        List<AuditEvidenceExportEvent> events = documents.stream()
                .map(document -> {
                    Long position = document.chainPosition();
                    AuditAnchorDocument local = position == null ? null : localAnchors.get(position);
                    ExternalAuditAnchor external = position == null ? null : externalAnchors.get(position);
                    return AuditEvidenceExportEvent.from(
                            document,
                            local == null ? null : AuditEvidenceExportAnchorReference.local(local),
                            external == null ? null : AuditEvidenceExportAnchorReference.external(external)
                    );
                })
                .toList();
        return new AuditEvidenceExportResponse(
                "AVAILABLE",
                events.size(),
                query.limit(),
                query.sourceService(),
                query.from(),
                query.to(),
                null,
                null,
                events
        );
    }

    private Map<Long, AuditAnchorDocument> localAnchors(AuditEvidenceExportQuery query, long minPosition, long maxPosition) {
        if (minPosition <= 0 || maxPosition <= 0) {
            return Map.of();
        }
        return anchorRepository.findByPartitionKeyAndChainPositionBetween(
                        query.partitionKey(),
                        minPosition,
                        maxPosition,
                        query.limit()
                ).stream()
                .collect(Collectors.toMap(AuditAnchorDocument::chainPosition, Function.identity(), (left, right) -> left));
    }

    private Map<Long, ExternalAuditAnchor> externalAnchors(AuditEvidenceExportQuery query) {
        try {
            return sink.findByRange(query.partitionKey(), query.from(), query.to(), query.limit()).stream()
                    .collect(Collectors.toMap(ExternalAuditAnchor::chainPosition, Function.identity(), (left, right) -> left));
        } catch (ExternalAuditAnchorSinkException exception) {
            log.warn("External audit anchors unavailable during evidence export: reason={}", exception.reason());
            return Map.of();
        }
    }

    private void auditEvidenceExport(
            AuditEvidenceExportQuery query,
            int countReturned,
            AuditOutcome outcome,
            String failureReason
    ) {
        auditService.audit(
                AuditAction.EXPORT_AUDIT_EVIDENCE,
                AuditResourceType.AUDIT_EVIDENCE_EXPORT,
                null,
                null,
                "audit-evidence-exporter",
                outcome,
                failureReason,
                AuditEventMetadataSummary.auditRead(
                        null,
                        "alert-service",
                        "1.0",
                        "GET /api/v1/audit/evidence/export",
                        "source_service=" + query.sourceService() + ";from=present;to=present;limit=" + query.limit(),
                        countReturned
                )
        );
    }
}
