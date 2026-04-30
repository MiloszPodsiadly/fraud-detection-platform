package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditAnchorDocument;
import com.frauddetection.alert.audit.AuditAnchorRepository;
import com.frauddetection.alert.audit.AuditEventBusinessSemantics;
import com.frauddetection.alert.audit.AuditEventDocument;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
import com.frauddetection.alert.audit.AuditEventRepository;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditPersistenceUnavailableException;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AuditEvidenceExportService {

    private static final Logger log = LoggerFactory.getLogger(AuditEvidenceExportService.class);
    private static final ObjectMapper CANONICAL_JSON = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private final AuditEventRepository eventRepository;
    private final AuditAnchorRepository anchorRepository;
    private final ExternalAuditAnchorSink sink;
    private final AuditEvidenceExportQueryParser queryParser;
    private final AlertServiceMetrics metrics;
    private final AuditService auditService;
    private final CurrentAnalystUser currentAnalystUser;
    private final AuditEvidenceExportRateLimiterStrategy rateLimiter;
    private final AuditEvidenceExportAbuseDetector abuseDetector;
    private final ExternalAuditAnchorPublicationStatusRepository publicationStatusRepository;

    private record ExternalAnchorLookup(String status, Map<Long, ExternalAuditAnchor> anchors) {
    }

    AuditEvidenceExportService(
            AuditEventRepository eventRepository,
            AuditAnchorRepository anchorRepository,
            ExternalAuditAnchorSink sink,
            AuditEvidenceExportQueryParser queryParser,
            AlertServiceMetrics metrics,
            AuditService auditService,
            CurrentAnalystUser currentAnalystUser,
            AuditEvidenceExportRateLimiterStrategy rateLimiter,
            AuditEvidenceExportAbuseDetector abuseDetector
    ) {
        this(
                eventRepository,
                anchorRepository,
                sink,
                queryParser,
                metrics,
                auditService,
                currentAnalystUser,
                rateLimiter,
                abuseDetector,
                null
        );
    }

    @Autowired
    public AuditEvidenceExportService(
            AuditEventRepository eventRepository,
            AuditAnchorRepository anchorRepository,
            ExternalAuditAnchorSink sink,
            AuditEvidenceExportQueryParser queryParser,
            AlertServiceMetrics metrics,
            AuditService auditService,
            CurrentAnalystUser currentAnalystUser,
            AuditEvidenceExportRateLimiterStrategy rateLimiter,
            AuditEvidenceExportAbuseDetector abuseDetector,
            ExternalAuditAnchorPublicationStatusRepository publicationStatusRepository
    ) {
        this.eventRepository = eventRepository;
        this.anchorRepository = anchorRepository;
        this.sink = sink;
        this.queryParser = queryParser;
        this.metrics = metrics;
        this.auditService = auditService;
        this.currentAnalystUser = currentAnalystUser;
        this.rateLimiter = rateLimiter;
        this.abuseDetector = abuseDetector;
        this.publicationStatusRepository = publicationStatusRepository;
    }

    public AuditEvidenceExportResponse export(String from, String to, String sourceService, Integer limit) {
        return export(from, to, sourceService, limit, false);
    }

    public AuditEvidenceExportResponse export(String from, String to, String sourceService, Integer limit, boolean strict) {
        AuditEvidenceExportQuery query = queryParser.parse(from, to, sourceService, limit);
        String actorId = currentActorId();
        if (!rateLimiter.allow(actorId)) {
            metrics.recordEvidenceExportRateLimited();
            auditEvidenceExport(query, 0, "RATE_LIMITED", "RATE_LIMITED", null, AuditEvidenceExportResponse.AnchorCoverage.empty(), null, AuditOutcome.FAILED);
            throw new AuditEvidenceExportRejectedException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "RATE_LIMITED",
                    "Audit evidence export rate limit exceeded.",
                    List.of("reason_code:RATE_LIMITED")
            );
        }
        try {
            List<AuditEventDocument> documents = eventRepository.findEvidenceWindow(
                    query.sourceService(),
                    query.from(),
                    query.to(),
                    query.limit()
            );
            AuditEvidenceExportResponse response = available(query, documents);
            abuseDetector.record(actorId, response.exportFingerprint());
            if (strict && "PARTIAL".equals(response.status())) {
                metrics.recordEvidenceExport(response.status());
                auditEvidenceExport(query, 0, "REJECTED_STRICT_MODE", response.reasonCode(), response.externalAnchorStatus(), response.anchorCoverage(), response.exportFingerprint(), AuditOutcome.FAILED);
                throw new AuditEvidenceExportRejectedException(
                        HttpStatus.CONFLICT,
                        response.reasonCode(),
                        "Strict audit evidence export rejected a partial evidence package.",
                        List.of("reason_code:" + response.reasonCode())
                );
            }
            metrics.recordEvidenceExport(response.status());
            auditEvidenceExport(query, response.count(), response.status(), response.reasonCode(), response.externalAnchorStatus(), response.anchorCoverage(), response.exportFingerprint(), AuditOutcome.SUCCESS);
            return response;
        } catch (DataAccessException exception) {
            AuditEvidenceExportResponse response = AuditEvidenceExportResponse.unavailable(query);
            metrics.recordEvidenceExport("UNAVAILABLE");
            try {
                auditEvidenceExport(query, response.count(), response.status(), response.reasonCode(), response.externalAnchorStatus(), response.anchorCoverage(), response.exportFingerprint(), AuditOutcome.FAILED);
            } catch (AuditPersistenceUnavailableException ignored) {
                log.warn("Audit evidence export access audit could not be persisted.");
            }
            return response;
        }
    }

    private AuditEvidenceExportResponse available(AuditEvidenceExportQuery query, List<AuditEventDocument> documents) {
        if (documents.isEmpty()) {
            return new AuditEvidenceExportResponse(
                    "AVAILABLE",
                    0,
                    query.limit(),
                    query.sourceService(),
                    query.from(),
                    query.to(),
                    null,
                    null,
                    "AVAILABLE",
                    AuditEvidenceExportResponse.AnchorCoverage.empty(),
                    fingerprint(query, AuditEvidenceExportResponse.AnchorCoverage.empty(), new ChainRange(null, null, null, false), List.of()),
                    null,
                    null,
                    null,
                    false,
                    List.of()
            );
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
        ExternalAnchorLookup externalAnchorLookup = externalAnchors(query);
        Map<String, AuditEventBusinessSemantics> semantics = AuditEventBusinessSemantics.index(documents);
        List<AuditEvidenceExportEvent> events = documents.stream()
                .map(document -> {
                    Long position = document.chainPosition();
                    AuditAnchorDocument local = position == null ? null : localAnchors.get(position);
                    ExternalAuditAnchor external = position == null ? null : externalAnchorLookup.anchors().get(position);
                    return AuditEvidenceExportEvent.from(
                            document,
                            local == null ? null : AuditEvidenceExportAnchorReference.local(local),
                            external == null ? null : externalEvidenceReference(external),
                            semantics.get(document.auditId())
                    );
                })
                .toList();
        AuditEvidenceExportResponse.AnchorCoverage coverage = coverage(events);
        String externalAnchorStatus = externalAnchorStatus(coverage, externalAnchorLookup.status());
        String reasonCode = reasonCode(coverage, externalAnchorStatus);
        String status = reasonCode == null ? "AVAILABLE" : "PARTIAL";
        ChainRange chainRange = chainRange(events);
        String fingerprint = fingerprint(query, coverage, chainRange, events);
        return new AuditEvidenceExportResponse(
                status,
                events.size(),
                query.limit(),
                query.sourceService(),
                query.from(),
                query.to(),
                reasonCode,
                message(reasonCode),
                externalAnchorStatus,
                coverage,
                fingerprint,
                chainRange.start(),
                chainRange.end(),
                chainRange.predecessorHash(),
                chainRange.partial(),
                events
        );
    }

    private ChainRange chainRange(List<AuditEvidenceExportEvent> events) {
        if (events.isEmpty()) {
            return new ChainRange(null, null, null, false);
        }
        AuditEvidenceExportEvent first = events.getFirst();
        AuditEvidenceExportEvent last = events.getLast();
        Long start = first.chainPosition();
        Long end = last.chainPosition();
        boolean partial = start != null && start > 1;
        return new ChainRange(start, end, partial ? first.previousEventHash() : null, partial);
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

    private ExternalAnchorLookup externalAnchors(AuditEvidenceExportQuery query) {
        if ("disabled".equals(sink.sinkType())) {
            return new ExternalAnchorLookup("DISABLED", Map.of());
        }
        try {
            Map<Long, ExternalAuditAnchor> anchors = sink.findByRange(query.partitionKey(), query.from(), query.to(), query.limit()).stream()
                    .collect(Collectors.toMap(ExternalAuditAnchor::chainPosition, Function.identity(), (left, right) -> left));
            return new ExternalAnchorLookup("AVAILABLE", anchors);
        } catch (ExternalAuditAnchorSinkException exception) {
            log.warn("External audit anchors unavailable during evidence export: reason={}", exception.reason());
            return new ExternalAnchorLookup("UNAVAILABLE", Map.of());
        }
    }

    private AuditEvidenceExportAnchorReference externalEvidenceReference(ExternalAuditAnchor external) {
        ExternalAnchorReference reference = null;
        try {
            reference = sink.externalReference(external).orElse(null);
            reference = enrichSignature(reference);
        } catch (ExternalAuditAnchorSinkException exception) {
            log.warn("External audit anchor reference unavailable during evidence export: reason={}", exception.reason());
        }
        return AuditEvidenceExportAnchorReference.external(external, reference, sink.immutabilityLevel());
    }

    private ExternalAnchorReference enrichSignature(ExternalAnchorReference reference) {
        if (reference == null || publicationStatusRepository == null || reference.anchorId() == null) {
            return reference;
        }
        try {
            return publicationStatusRepository.findByLocalAnchorId(reference.anchorId())
                    .map(status -> reference.withSignature(new SignedAuditAnchorPayload(
                            status.signatureStatus(),
                            status.signingAlgorithm(),
                            status.signature(),
                            status.signingKeyId(),
                            status.signedAt(),
                            status.signingAuthority(),
                            status.signedPayloadHash()
                    )))
                    .orElse(reference);
        } catch (DataAccessException exception) {
            return reference;
        }
    }

    private AuditEvidenceExportResponse.AnchorCoverage coverage(List<AuditEvidenceExportEvent> events) {
        int localAvailable = 0;
        int externalAvailable = 0;
        for (AuditEvidenceExportEvent event : events) {
            if (event.localAnchor() != null) {
                localAvailable++;
            }
            if (event.externalAnchor() != null) {
                externalAvailable++;
            }
        }
        int total = events.size();
        int missingExternal = total - externalAvailable;
        double coverageRatio = total == 0 ? 1.0d : (double) externalAvailable / (double) total;
        return new AuditEvidenceExportResponse.AnchorCoverage(
                total,
                localAvailable,
                externalAvailable,
                missingExternal,
                coverageRatio
        );
    }

    private String externalAnchorStatus(AuditEvidenceExportResponse.AnchorCoverage coverage, String lookupStatus) {
        if ("DISABLED".equals(lookupStatus) || "UNAVAILABLE".equals(lookupStatus)) {
            return lookupStatus;
        }
        if (coverage.eventsMissingExternalAnchor() > 0) {
            return "PARTIAL";
        }
        return "AVAILABLE";
    }

    private String reasonCode(AuditEvidenceExportResponse.AnchorCoverage coverage, String externalAnchorStatus) {
        if (coverage.eventsWithLocalAnchor() < coverage.totalEvents()) {
            return "INTERNAL_ERROR";
        }
        if ("DISABLED".equals(externalAnchorStatus) || "UNAVAILABLE".equals(externalAnchorStatus)) {
            return "EXTERNAL_ANCHORS_UNAVAILABLE";
        }
        if (coverage.eventsWithExternalAnchor() == 0 && coverage.totalEvents() > 0) {
            return "EXTERNAL_ANCHORS_UNAVAILABLE";
        }
        if (coverage.eventsMissingExternalAnchor() > 0) {
            return "EXTERNAL_ANCHOR_GAPS";
        }
        return null;
    }

    private String message(String reasonCode) {
        if (reasonCode == null) {
            return null;
        }
        return switch (reasonCode) {
            case "INTERNAL_ERROR" -> "Audit evidence export is incomplete due to missing local anchor coverage.";
            case "EXTERNAL_ANCHORS_UNAVAILABLE" -> "External audit anchors could not be loaded; export is incomplete as an evidence package.";
            case "EXTERNAL_ANCHOR_GAPS" -> "Some exported audit events do not have matching external anchor references.";
            default -> "Audit evidence export is partial.";
        };
    }

    private String currentActorId() {
        Optional<AnalystPrincipal> principal = currentAnalystUser.get();
        return principal == null ? "unknown" : principal.map(value -> value.userId()).orElse("unknown");
    }

    private String fingerprint(
            AuditEvidenceExportQuery query,
            AuditEvidenceExportResponse.AnchorCoverage coverage,
            ChainRange chainRange,
            List<AuditEvidenceExportEvent> events
    ) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("query", Map.of(
                "from", query.from().toString(),
                "to", query.to().toString(),
                "source_service", query.sourceService(),
                "limit", query.limit()
        ));
        canonical.put("audit_event_ids", events.stream().map(AuditEvidenceExportEvent::auditEventId).toList());
        canonical.put("event_hashes", events.stream().map(AuditEvidenceExportEvent::eventHash).toList());
        canonical.put("business_effective", events.stream().map(AuditEvidenceExportEvent::businessEffective).toList());
        canonical.put("compensated", events.stream().map(AuditEvidenceExportEvent::compensated).toList());
        canonical.put("local_anchor_ids", events.stream()
                .map(AuditEvidenceExportEvent::localAnchor)
                .map(anchor -> anchor == null ? null : anchor.anchorId())
                .toList());
        canonical.put("external_anchor_ids", events.stream()
                .map(AuditEvidenceExportEvent::externalAnchor)
                .map(anchor -> anchor == null ? null : anchor.externalAnchorId())
                .toList());
        canonical.put("anchor_coverage", Map.of(
                "coverage_ratio", coverage.coverageRatio(),
                "events_missing_external_anchor", coverage.eventsMissingExternalAnchor(),
                "events_with_external_anchor", coverage.eventsWithExternalAnchor(),
                "events_with_local_anchor", coverage.eventsWithLocalAnchor(),
                "total_events", coverage.totalEvents()
        ));
        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("chain_range_start", chainRange.start());
        chain.put("chain_range_end", chainRange.end());
        chain.put("partial_chain_range", chainRange.partial());
        chain.put("predecessor_hash", chainRange.predecessorHash());
        canonical.put("chain_range", chain);
        try {
            byte[] canonicalJson = CANONICAL_JSON.writeValueAsBytes(canonical);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalJson));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Audit evidence export fingerprint could not be computed.");
        }
    }

    private void auditEvidenceExport(
            AuditEvidenceExportQuery query,
            int countReturned,
            String exportStatus,
            String reasonCode,
            String externalAnchorStatus,
            AuditEvidenceExportResponse.AnchorCoverage anchorCoverage,
            String exportFingerprint,
            AuditOutcome outcome
    ) {
        auditService.audit(
                AuditAction.EXPORT_AUDIT_EVIDENCE,
                AuditResourceType.AUDIT_EVIDENCE_EXPORT,
                null,
                null,
                "audit-evidence-exporter",
                outcome,
                reasonCode,
                AuditEventMetadataSummary.evidenceExport(
                        "alert-service",
                        "1.0",
                        query.from().toString(),
                        query.to().toString(),
                        query.limit(),
                        countReturned,
                        exportStatus,
                        reasonCode,
                        externalAnchorStatus,
                        new AuditEventMetadataSummary.AnchorCoverageSummary(
                                anchorCoverage.totalEvents(),
                                anchorCoverage.eventsWithLocalAnchor(),
                                anchorCoverage.eventsWithExternalAnchor(),
                                anchorCoverage.eventsMissingExternalAnchor(),
                                anchorCoverage.coverageRatio()
                        ),
                        exportFingerprint
                )
        );
    }

    private record ChainRange(
            Long start,
            Long end,
            String predecessorHash,
            boolean partial
    ) {
    }
}
