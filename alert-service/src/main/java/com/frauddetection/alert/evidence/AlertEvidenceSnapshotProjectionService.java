package com.frauddetection.alert.evidence;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.evidence.ScoringEvidenceItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AlertEvidenceSnapshotProjectionService {

    private static final Logger log = LoggerFactory.getLogger(AlertEvidenceSnapshotProjectionService.class);

    private final ScoringEvidenceSnapshotMapper mapper;
    private final Clock clock;
    private final AlertEvidenceSnapshotProperties properties;
    private final AlertServiceMetrics metrics;

    @Autowired
    public AlertEvidenceSnapshotProjectionService(
            ScoringEvidenceSnapshotMapper mapper,
            AlertEvidenceSnapshotProperties properties,
            AlertServiceMetrics metrics
    ) {
        this(mapper, properties, metrics, Clock.systemUTC());
    }

    AlertEvidenceSnapshotProjectionService(
            ScoringEvidenceSnapshotMapper mapper,
            AlertEvidenceSnapshotProperties properties,
            AlertServiceMetrics metrics,
            Clock clock
    ) {
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
        this.properties = properties == null ? new AlertEvidenceSnapshotProperties(null) : properties;
        this.metrics = Objects.requireNonNull(metrics, "metrics is required");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public List<EvidenceSnapshotItem> projectOrDiagnostic(TransactionScoredEvent event) {
        try {
            return project(event);
        } catch (RuntimeException exception) {
            metrics.recordEvidenceSnapshotProjectionError();
            log.atWarn()
                    .addKeyValue("outcome", "error")
                    .addKeyValue("state", EvidenceProjectionState.ERROR_PROJECTION_FAILED.name())
                    .addKeyValue("exceptionType", exception.getClass().getSimpleName())
                    .log("Alert evidence snapshot projection failed.");
            return List.of(projectionFailureDiagnostic(event, exception));
        }
    }

    public List<EvidenceSnapshotItem> project(TransactionScoredEvent event) {
        Objects.requireNonNull(event, "event is required");

        EvidenceProjectionState lineageState = lineageState(event);
        if (lineageState != null) {
            List<EvidenceSnapshotItem> snapshot = List.of(lineageDiagnostic(event, lineageState));
            recordProjectionOutcome(snapshot);
            return snapshot;
        }

        List<ScoringEvidenceItem> scoringEvidence = event.scoringEvidence();
        if (scoringEvidence == null || scoringEvidence.isEmpty()) {
            if (isHighOrCritical(event.riskLevel()) || Boolean.TRUE.equals(event.alertRecommended())) {
                List<EvidenceSnapshotItem> snapshot = List.of(emptyScoringEvidenceDiagnostic(event));
                recordProjectionOutcome(snapshot);
                return snapshot;
            }
            metrics.recordEvidenceSnapshotProjectionSuccess();
            return List.of();
        }

        int maxItems = properties.maxItems();
        int incomingCount = scoringEvidence.size();
        boolean willTruncate = incomingCount > maxItems;
        int retainLimit = willTruncate ? maxItems - 1 : maxItems;
        List<EvidenceSnapshotItem> projected = new ArrayList<>(Math.min(retainLimit, incomingCount));
        for (int index = 0; index < scoringEvidence.size() && projected.size() < retainLimit; index++) {
            ScoringEvidenceItem item = scoringEvidence.get(index);
            if (item != null) {
                projected.add(projectItem(event, item, index));
            }
        }
        if (willTruncate) {
            projected.add(truncationDiagnostic(event, incomingCount, projected.size()));
        }
        List<EvidenceSnapshotItem> snapshot = List.copyOf(projected);
        recordProjectionOutcome(snapshot);
        return snapshot;
    }

    private EvidenceSnapshotItem projectItem(TransactionScoredEvent event, ScoringEvidenceItem item, int index) {
        try {
            EvidenceStatus status = mapper.mapStatus(item.status());
            EvidenceType evidenceType = mapper.mapType(item.evidenceType());
            EvidenceSource source = mapper.mapSource(item.source());
            EvidenceSeverity severity = mapper.mapSeverity(item.severity());
            if (status == EvidenceStatus.AVAILABLE && (!hasText(item.reasonCode()) || isUnknown(item.reasonCode()) || evidenceType == EvidenceType.DIAGNOSTIC)) {
                return projectionDiagnostic(
                        event,
                        EvidenceProjectionState.UNAVAILABLE_UNSUPPORTED_EVIDENCE,
                        "Unsupported scoring evidence snapshot",
                        "Scoring evidence could not be projected as available alert evidence.",
                        "unsupported_scoring_evidence",
                        index,
                        EvidenceStatus.UNAVAILABLE,
                        Map.of("unsupportedEvidencePresent", true)
                );
            }

            Map<String, Object> attributes = new LinkedHashMap<>(item.attributes());
            attributes.put("evidenceProjectionState", projectionState(status).name());

            return new EvidenceSnapshotItem(
                    snapshotEvidenceId(event, item.evidenceId(), index),
                    event.eventId(),
                    event.transactionId(),
                    event.correlationId(),
                    item.reasonCode(),
                    evidenceType,
                    source,
                    status,
                    severity,
                    item.title(),
                    item.description(),
                    item.value(),
                    item.baselineValue(),
                    attributes,
                    item.observedAt(),
                    clock.instant(),
                    event.scoringStrategy(),
                    event.modelName(),
                    event.modelVersion(),
                    event.inferenceTimestamp()
            );
        } catch (RuntimeException exception) {
            return projectionDiagnostic(
                    event,
                    EvidenceProjectionState.ERROR_PROJECTION_FAILED,
                    "Alert evidence snapshot projection failed",
                    "Scoring evidence item could not be projected into alert evidence snapshot.",
                    "projection_failed",
                    index,
                    EvidenceStatus.ERROR,
                    Map.of(
                            "projectionError", true,
                            "projectionErrorType", exception.getClass().getSimpleName(),
                            "invalidScoringEvidenceIndex", index
                    )
            );
        }
    }

    private EvidenceSnapshotItem truncationDiagnostic(TransactionScoredEvent event, int originalCount, int retainedCount) {
        return projectionDiagnostic(
                event,
                EvidenceProjectionState.PARTIAL_TRUNCATED,
                "Alert evidence snapshot truncated",
                "Snapshot exceeded configured item limit; retained bounded projection with diagnostic marker.",
                "snapshot_truncated",
                retainedCount,
                EvidenceStatus.PARTIAL,
                Map.of(
                        "originalEvidenceCount", originalCount,
                        "retainedEvidenceCount", retainedCount,
                        "truncatedEvidenceCount", originalCount - retainedCount,
                        "maxEvidenceSnapshotItems", properties.maxItems()
                )
        );
    }

    private EvidenceSnapshotItem lineageDiagnostic(TransactionScoredEvent event, EvidenceProjectionState state) {
        return projectionDiagnostic(
                event,
                state,
                "Alert evidence snapshot missing lineage",
                "Alert evidence snapshot projection could not create available evidence because required lineage is missing.",
                "missing_required_lineage",
                0,
                EvidenceStatus.PARTIAL,
                Map.of(
                        "missingSourceEventId", !hasText(event.eventId()),
                        "missingTransactionId", !hasText(event.transactionId()),
                        "missingCorrelationId", !hasText(event.correlationId())
                )
        );
    }

    private EvidenceSnapshotItem emptyScoringEvidenceDiagnostic(TransactionScoredEvent event) {
        return projectionDiagnostic(
                event,
                EvidenceProjectionState.PARTIAL_EMPTY_SCORING_EVIDENCE,
                "Alert evidence snapshot missing scoring evidence",
                "Alert creation received no scoring evidence for a high-risk or alert-recommended transaction.",
                "empty_scoring_evidence",
                0,
                EvidenceStatus.PARTIAL,
                Map.of("scoringEvidenceCount", 0)
        );
    }

    private EvidenceSnapshotItem projectionDiagnostic(
            TransactionScoredEvent event,
            EvidenceProjectionState state,
            String title,
            String description,
            String code,
            int index,
            EvidenceStatus status,
            Map<String, Object> extraAttributes
    ) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("diagnostic", true);
        attributes.put("supportedEvidenceCreated", false);
        attributes.put("reasonCodeApplicable", false);
        attributes.put("evidenceProjectionState", state.name());
        attributes.put("diagnosticIndex", index);
        attributes.putAll(extraAttributes);
        return new EvidenceSnapshotItem(
                snapshotEvidenceId(event, code, index),
                event == null ? null : event.eventId(),
                event == null ? null : event.transactionId(),
                event == null ? null : event.correlationId(),
                null,
                EvidenceType.DIAGNOSTIC,
                EvidenceSource.ALERT_SERVICE,
                status,
                EvidenceSeverity.LOW,
                title,
                description,
                code,
                null,
                attributes,
                event == null ? null : event.inferenceTimestamp(),
                clock.instant(),
                event == null ? null : event.scoringStrategy(),
                event == null ? null : event.modelName(),
                event == null ? null : event.modelVersion(),
                event == null ? null : event.inferenceTimestamp()
        );
    }

    private EvidenceSnapshotItem projectionFailureDiagnostic(TransactionScoredEvent event, RuntimeException exception) {
        return projectionDiagnostic(
                event,
                EvidenceProjectionState.ERROR_PROJECTION_FAILED,
                "Alert evidence snapshot projection failed",
                "Alert evidence snapshot projection failed and created an error diagnostic.",
                "projection_failed",
                0,
                EvidenceStatus.ERROR,
                Map.of(
                        "projectionError", true,
                        "projectionErrorType", exception.getClass().getSimpleName()
                )
        );
    }

    private EvidenceProjectionState lineageState(TransactionScoredEvent event) {
        boolean missingSourceEventId = !hasText(event.eventId());
        boolean missingTransactionId = !hasText(event.transactionId());
        boolean missingCorrelationId = !hasText(event.correlationId());
        int missingCount = (missingSourceEventId ? 1 : 0) + (missingTransactionId ? 1 : 0) + (missingCorrelationId ? 1 : 0);
        if (missingCount == 0) {
            return null;
        }
        if (missingCount > 1) {
            return EvidenceProjectionState.PARTIAL_MISSING_REQUIRED_LINEAGE;
        }
        if (missingSourceEventId) {
            return EvidenceProjectionState.PARTIAL_MISSING_SOURCE_EVENT_ID;
        }
        if (missingTransactionId) {
            return EvidenceProjectionState.PARTIAL_MISSING_TRANSACTION_ID;
        }
        return EvidenceProjectionState.PARTIAL_MISSING_CORRELATION_ID;
    }

    private EvidenceProjectionState projectionState(EvidenceStatus status) {
        return switch (status) {
            case AVAILABLE -> EvidenceProjectionState.PROJECTED;
            case LEGACY -> EvidenceProjectionState.LEGACY_PROJECTED;
            case ERROR -> EvidenceProjectionState.ERROR_PROJECTED;
            case PARTIAL, UNAVAILABLE, NOT_APPLICABLE, STALE -> EvidenceProjectionState.UNAVAILABLE_UNSUPPORTED_EVIDENCE;
        };
    }

    private void recordProjectionOutcome(List<EvidenceSnapshotItem> snapshot) {
        boolean hasError = false;
        boolean hasTruncation = false;
        EvidenceProjectionState firstDiagnosticState = null;
        for (EvidenceSnapshotItem item : snapshot) {
            EvidenceProjectionState state = stateFrom(item);
            if (state == EvidenceProjectionState.ERROR_PROJECTION_FAILED) {
                hasError = true;
            }
            if (state == EvidenceProjectionState.PARTIAL_TRUNCATED) {
                hasTruncation = true;
            }
            if (firstDiagnosticState == null && item.evidenceType() == EvidenceType.DIAGNOSTIC) {
                firstDiagnosticState = state;
            }
        }
        if (hasError) {
            metrics.recordEvidenceSnapshotProjectionError();
            log.atWarn()
                    .addKeyValue("outcome", "error")
                    .addKeyValue("count", snapshot.size())
                    .log("Alert evidence snapshot projection produced error diagnostic.");
            return;
        }
        if (hasTruncation) {
            metrics.recordEvidenceSnapshotProjectionTruncated();
            log.atInfo()
                    .addKeyValue("outcome", "truncated")
                    .addKeyValue("state", EvidenceProjectionState.PARTIAL_TRUNCATED.name())
                    .addKeyValue("count", snapshot.size())
                    .log("Alert evidence snapshot projection truncated input.");
            return;
        }
        if (firstDiagnosticState != null) {
            metrics.recordEvidenceSnapshotProjectionDiagnostic(firstDiagnosticState);
            log.atInfo()
                    .addKeyValue("outcome", "diagnostic")
                    .addKeyValue("state", firstDiagnosticState.name())
                    .addKeyValue("count", snapshot.size())
                    .log("Alert evidence snapshot projection produced diagnostic.");
            return;
        }
        metrics.recordEvidenceSnapshotProjectionSuccess();
    }

    private EvidenceProjectionState stateFrom(EvidenceSnapshotItem item) {
        Object state = item.attributes().get("evidenceProjectionState");
        if (state instanceof String stateName) {
            try {
                return EvidenceProjectionState.valueOf(stateName);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean isHighOrCritical(RiskLevel riskLevel) {
        return riskLevel == RiskLevel.HIGH || riskLevel == RiskLevel.CRITICAL;
    }

    private String snapshotEvidenceId(TransactionScoredEvent event, String evidenceId, int index) {
        return "%s:%s:%s".formatted(
                safeId(event == null ? null : event.eventId(), "missing-event"),
                safeId(evidenceId, "missing-evidence"),
                index
        );
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean isUnknown(String value) {
        return "UNKNOWN".equals(value.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private String safeId(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }
}
