package com.frauddetection.alert.evidence;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.evidence.ScoringEvidenceItem;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final ScoringEvidenceSnapshotMapper mapper;
    private final Clock clock;
    private final AlertEvidenceSnapshotProperties properties;

    @Autowired
    public AlertEvidenceSnapshotProjectionService(
            ScoringEvidenceSnapshotMapper mapper,
            AlertEvidenceSnapshotProperties properties
    ) {
        this(mapper, properties, Clock.systemUTC());
    }

    AlertEvidenceSnapshotProjectionService(
            ScoringEvidenceSnapshotMapper mapper,
            AlertEvidenceSnapshotProperties properties,
            Clock clock
    ) {
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
        this.properties = properties == null ? new AlertEvidenceSnapshotProperties(null) : properties;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public List<EvidenceSnapshotItem> project(TransactionScoredEvent event) {
        Objects.requireNonNull(event, "event is required");

        EvidenceProjectionState lineageState = lineageState(event);
        if (lineageState != null) {
            return List.of(lineageDiagnostic(event, lineageState));
        }

        List<ScoringEvidenceItem> scoringEvidence = event.scoringEvidence();
        if (scoringEvidence == null || scoringEvidence.isEmpty()) {
            if (isHighOrCritical(event.riskLevel()) || Boolean.TRUE.equals(event.alertRecommended())) {
                return List.of(emptyScoringEvidenceDiagnostic(event));
            }
            return List.of();
        }

        List<EvidenceSnapshotItem> projected = new ArrayList<>();
        for (int index = 0; index < scoringEvidence.size(); index++) {
            ScoringEvidenceItem item = scoringEvidence.get(index);
            if (item != null) {
                projected.add(projectItem(event, item, index));
            }
        }
        return bounded(projected, event);
    }

    private EvidenceSnapshotItem projectItem(TransactionScoredEvent event, ScoringEvidenceItem item, int index) {
        EvidenceStatus status = mapper.mapStatus(item.status());
        EvidenceType evidenceType = mapper.mapType(item.evidenceType());
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
        if (hasText(event.scoringStrategy())) {
            attributes.put("scoringStrategy", event.scoringStrategy());
        }
        if (hasText(event.modelName())) {
            attributes.put("modelName", event.modelName());
        }
        if (hasText(event.modelVersion())) {
            attributes.put("modelVersion", event.modelVersion());
        }

        return new EvidenceSnapshotItem(
                snapshotEvidenceId(event, item.evidenceId(), index),
                event.eventId(),
                event.transactionId(),
                event.correlationId(),
                item.reasonCode(),
                evidenceType,
                mapper.mapSource(item.source()),
                status,
                mapper.mapSeverity(item.severity()),
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
    }

    private List<EvidenceSnapshotItem> bounded(List<EvidenceSnapshotItem> projected, TransactionScoredEvent event) {
        int maxItems = properties.maxItems();
        if (projected.size() <= maxItems) {
            return List.copyOf(projected);
        }
        int retainedCount = maxItems - 1;
        List<EvidenceSnapshotItem> bounded = new ArrayList<>(projected.subList(0, retainedCount));
        bounded.add(truncationDiagnostic(event, projected.size(), retainedCount));
        return List.copyOf(bounded);
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
                event.eventId(),
                event.transactionId(),
                event.correlationId(),
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
                event.inferenceTimestamp(),
                clock.instant(),
                event.scoringStrategy(),
                event.modelName(),
                event.modelVersion(),
                event.inferenceTimestamp()
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
            case LEGACY -> EvidenceProjectionState.LEGACY_NOT_PROJECTED;
            case ERROR -> EvidenceProjectionState.ERROR_PROJECTION_FAILED;
            case PARTIAL, UNAVAILABLE, NOT_APPLICABLE, STALE -> EvidenceProjectionState.UNAVAILABLE_UNSUPPORTED_EVIDENCE;
        };
    }

    private boolean isHighOrCritical(RiskLevel riskLevel) {
        return riskLevel == RiskLevel.HIGH || riskLevel == RiskLevel.CRITICAL;
    }

    private String snapshotEvidenceId(TransactionScoredEvent event, String evidenceId, int index) {
        return "%s:%s:%s".formatted(
                safeId(event.eventId(), "missing-event"),
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
