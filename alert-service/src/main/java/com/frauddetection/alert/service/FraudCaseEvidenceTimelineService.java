package com.frauddetection.alert.service;

import com.frauddetection.alert.api.FraudCaseEvidenceTimelineResponse;
import com.frauddetection.alert.api.FraudCaseTimelineEventResponse;
import com.frauddetection.alert.api.FraudCaseTimelineEventType;
import com.frauddetection.alert.api.FraudCaseTimelineLinkedEntityType;
import com.frauddetection.alert.evidence.EvidenceSnapshotItem;
import com.frauddetection.alert.evidence.EvidenceSource;
import com.frauddetection.alert.evidence.EvidenceStatus;
import com.frauddetection.alert.fraudcase.FraudCaseNotFoundException;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class FraudCaseEvidenceTimelineService {

    static final int MAX_TIMELINE_EVENTS = 100;
    static final int MAX_LINKED_ALERTS_FOR_TIMELINE = 50;
    static final String TIMELINE_EVENT_LIMIT_EXCEEDED = "TIMELINE_EVENT_LIMIT_EXCEEDED";

    private final FraudCaseRepository fraudCaseRepository;
    private final AlertRepository alertRepository;
    private final Clock clock;

    @Autowired
    public FraudCaseEvidenceTimelineService(FraudCaseRepository fraudCaseRepository, AlertRepository alertRepository) {
        this(fraudCaseRepository, alertRepository, Clock.systemUTC());
    }

    FraudCaseEvidenceTimelineService(FraudCaseRepository fraudCaseRepository, AlertRepository alertRepository, Clock clock) {
        this.fraudCaseRepository = fraudCaseRepository;
        this.alertRepository = alertRepository;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public FraudCaseEvidenceTimelineResponse timeline(String caseId) {
        FraudCaseDocument fraudCase = fraudCaseRepository.findById(caseId)
                .orElseThrow(() -> new FraudCaseNotFoundException(caseId));
        List<String> allLinkedAlertIds = normalizedLinkedAlertIds(fraudCase);
        boolean linkedAlertInputTruncated = allLinkedAlertIds.size() > MAX_LINKED_ALERTS_FOR_TIMELINE;
        List<String> linkedAlertIds = allLinkedAlertIds.stream()
                .limit(MAX_LINKED_ALERTS_FOR_TIMELINE)
                .toList();
        boolean legacy = allLinkedAlertIds.isEmpty();
        boolean partial = false;
        List<EventDraft> drafts = new ArrayList<>();
        int sequence = 0;

        if (fraudCase.getCreatedAt() != null) {
            drafts.add(EventDraft.fraudCaseCreated(++sequence, fraudCase.getCreatedAt()));
        }

        if (legacy) {
            drafts.add(EventDraft.legacy(++sequence, fraudCase.getCreatedAt(), fraudCase.getCreatedAt() == null));
            partial = fraudCase.getCreatedAt() == null;
        } else {
            Map<String, AlertDocument> alertsById = alertsById(linkedAlertIds);
            partial = linkedAlertInputTruncated || alertsById.size() < linkedAlertIds.size();
            for (String alertId : linkedAlertIds) {
                AlertDocument alert = alertsById.get(alertId);
                if (alert == null) {
                    continue;
                }
                TimestampChoice alertTime = alertTime(alert);
                drafts.add(EventDraft.linkedAlertContext(++sequence, alertTime.occurredAt(), alertTime.approximate()));
                SnapshotEvent snapshot = snapshotEvent(alert, alertTime);
                drafts.add(EventDraft.evidenceSnapshot(++sequence, snapshot));
                partial = partial || alertTime.approximate() || snapshot.partial();
            }
        }

        List<EventDraft> sorted = drafts.stream()
                .sorted(eventComparator())
                .toList();
        boolean truncated = linkedAlertInputTruncated || sorted.size() > MAX_TIMELINE_EVENTS;
        if (truncated) {
            partial = true;
            if (sorted.size() > MAX_TIMELINE_EVENTS) {
                sorted = sorted.subList(0, MAX_TIMELINE_EVENTS);
            }
        }
        List<FraudCaseTimelineEventResponse> events = toResponses(sorted);

        return new FraudCaseEvidenceTimelineResponse(
                fraudCase.getCaseId(),
                events,
                partial,
                legacy,
                truncated,
                truncated ? TIMELINE_EVENT_LIMIT_EXCEEDED : null,
                Instant.now(clock)
        );
    }

    private Map<String, AlertDocument> alertsById(List<String> alertIds) {
        if (alertIds.isEmpty()) {
            return Map.of();
        }
        return alertRepository.findAllById(alertIds).stream()
                .filter(alert -> StringUtils.hasText(alert.getAlertId()))
                .collect(Collectors.toUnmodifiableMap(AlertDocument::getAlertId, Function.identity(), (left, right) -> left));
    }

    private List<String> normalizedLinkedAlertIds(FraudCaseDocument fraudCase) {
        if (fraudCase.getLinkedAlertIds() == null) {
            return List.of();
        }
        return fraudCase.getLinkedAlertIds().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private TimestampChoice alertTime(AlertDocument alert) {
        if (alert.getAlertTimestamp() != null) {
            return new TimestampChoice(alert.getAlertTimestamp(), false);
        }
        if (alert.getCreatedAt() != null) {
            return new TimestampChoice(alert.getCreatedAt(), false);
        }
        return new TimestampChoice(null, true);
    }

    private SnapshotEvent snapshotEvent(AlertDocument alert, TimestampChoice alertTime) {
        List<EvidenceSnapshotItem> snapshot = alert.getEvidenceSnapshot();
        if (snapshot == null || snapshot.isEmpty()) {
            return new SnapshotEvent(
                    FraudCaseTimelineEventType.ALERT_EVIDENCE_SNAPSHOT_UNAVAILABLE,
                    alertTime.occurredAt(),
                    EvidenceStatus.UNAVAILABLE,
                    EvidenceSource.ALERT_SERVICE,
                    title(FraudCaseTimelineEventType.ALERT_EVIDENCE_SNAPSHOT_UNAVAILABLE),
                    description(FraudCaseTimelineEventType.ALERT_EVIDENCE_SNAPSHOT_UNAVAILABLE),
                    alertTime.approximate(),
                    alertTime.approximate()
            );
        }
        EvidenceStatus status = aggregateEvidenceStatus(snapshot);
        FraudCaseTimelineEventType eventType = status == EvidenceStatus.AVAILABLE
                ? FraudCaseTimelineEventType.ALERT_EVIDENCE_SNAPSHOT_AVAILABLE
                : FraudCaseTimelineEventType.ALERT_EVIDENCE_SNAPSHOT_PARTIAL;
        TimestampChoice evidenceTime = evidenceTime(snapshot, alertTime);
        return new SnapshotEvent(
                eventType,
                evidenceTime.occurredAt(),
                status,
                snapshotSource(snapshot),
                title(eventType),
                description(eventType),
                evidenceTime.approximate(),
                evidenceTime.approximate() || status != EvidenceStatus.AVAILABLE
        );
    }

    private EvidenceStatus aggregateEvidenceStatus(List<EvidenceSnapshotItem> snapshot) {
        if (snapshot.stream().anyMatch(item -> item.status() == EvidenceStatus.ERROR)) {
            return EvidenceStatus.ERROR;
        }
        boolean allAvailable = snapshot.stream().allMatch(item -> item.status() == EvidenceStatus.AVAILABLE);
        return allAvailable ? EvidenceStatus.AVAILABLE : EvidenceStatus.PARTIAL;
    }

    private EvidenceSource snapshotSource(List<EvidenceSnapshotItem> snapshot) {
        return snapshot.stream()
                .map(EvidenceSnapshotItem::source)
                .filter(source -> source != null)
                .findFirst()
                .orElse(EvidenceSource.ALERT_SERVICE);
    }

    private TimestampChoice evidenceTime(List<EvidenceSnapshotItem> snapshot, TimestampChoice alertTime) {
        Instant earliest = snapshot.stream()
                .map(this::itemTime)
                .filter(time -> time != null)
                .min(Comparator.naturalOrder())
                .orElse(null);
        if (earliest != null) {
            return new TimestampChoice(earliest, false);
        }
        return new TimestampChoice(alertTime.occurredAt(), true);
    }

    private Instant itemTime(EvidenceSnapshotItem item) {
        if (item.observedAt() != null) {
            return item.observedAt();
        }
        if (item.inferenceTimestamp() != null) {
            return item.inferenceTimestamp();
        }
        return item.projectedAt();
    }

    private Comparator<EventDraft> eventComparator() {
        return Comparator
                .comparing(EventDraft::occurredAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(event -> eventTypePriority(event.eventType()))
                .thenComparingInt(EventDraft::sourceSequence);
    }

    private int eventTypePriority(FraudCaseTimelineEventType eventType) {
        return switch (eventType) {
            case FRAUD_CASE_CREATED -> 0;
            case LINKED_ALERT_CONTEXT -> 10;
            case ALERT_EVIDENCE_SNAPSHOT_AVAILABLE -> 20;
            case ALERT_EVIDENCE_SNAPSHOT_PARTIAL -> 21;
            case ALERT_EVIDENCE_SNAPSHOT_UNAVAILABLE -> 22;
            case LEGACY_CONTEXT -> 90;
        };
    }

    private List<FraudCaseTimelineEventResponse> toResponses(List<EventDraft> events) {
        List<FraudCaseTimelineEventResponse> responses = new ArrayList<>();
        for (int index = 0; index < events.size(); index++) {
            EventDraft event = events.get(index);
            responses.add(new FraudCaseTimelineEventResponse(
                    eventKey(event.eventType(), index + 1),
                    event.eventType(),
                    event.occurredAt(),
                    event.source(),
                    event.evidenceStatus(),
                    event.title(),
                    event.description(),
                    event.linkedEntityType(),
                    event.approximateTime()
            ));
        }
        return List.copyOf(responses);
    }

    private String eventKey(FraudCaseTimelineEventType eventType, int ordinal) {
        return eventType.name() + "_" + String.format("%06d", ordinal);
    }

    private static String title(FraudCaseTimelineEventType eventType) {
        return switch (eventType) {
            case FRAUD_CASE_CREATED -> "Fraud case created";
            case LINKED_ALERT_CONTEXT -> "Linked alert context";
            case ALERT_EVIDENCE_SNAPSHOT_AVAILABLE -> "Alert evidence snapshot available";
            case ALERT_EVIDENCE_SNAPSHOT_PARTIAL -> "Alert evidence snapshot partial";
            case ALERT_EVIDENCE_SNAPSHOT_UNAVAILABLE -> "Alert evidence snapshot unavailable";
            case LEGACY_CONTEXT -> "Legacy case context";
        };
    }

    private static String description(FraudCaseTimelineEventType eventType) {
        return switch (eventType) {
            case FRAUD_CASE_CREATED -> "Read-only timeline event derived from existing fraud-case read data.";
            case LINKED_ALERT_CONTEXT -> "Read-only linked alert context derived from existing alert read data.";
            case ALERT_EVIDENCE_SNAPSHOT_AVAILABLE, ALERT_EVIDENCE_SNAPSHOT_PARTIAL ->
                    "Bounded evidence snapshot context derived from linked alert data.";
            case ALERT_EVIDENCE_SNAPSHOT_UNAVAILABLE ->
                    "Structured evidence snapshot was unavailable for this linked alert.";
            case LEGACY_CONTEXT ->
                    "Legacy case may not have structured evidence timeline data.";
        };
    }

    private record TimestampChoice(Instant occurredAt, boolean approximate) {
    }

    private record SnapshotEvent(
            FraudCaseTimelineEventType eventType,
            Instant occurredAt,
            EvidenceStatus evidenceStatus,
            EvidenceSource source,
            String title,
            String description,
            boolean approximateTime,
            boolean partial
    ) {
    }

    private record EventDraft(
            int sourceSequence,
            FraudCaseTimelineEventType eventType,
            Instant occurredAt,
            EvidenceSource source,
            EvidenceStatus evidenceStatus,
            String title,
            String description,
            FraudCaseTimelineLinkedEntityType linkedEntityType,
            boolean approximateTime
    ) {
        static EventDraft fraudCaseCreated(int sequence, Instant occurredAt) {
            return new EventDraft(
                    sequence,
                    FraudCaseTimelineEventType.FRAUD_CASE_CREATED,
                    occurredAt,
                    EvidenceSource.ALERT_SERVICE,
                    EvidenceStatus.AVAILABLE,
                    FraudCaseEvidenceTimelineService.title(FraudCaseTimelineEventType.FRAUD_CASE_CREATED),
                    FraudCaseEvidenceTimelineService.description(FraudCaseTimelineEventType.FRAUD_CASE_CREATED),
                    FraudCaseTimelineLinkedEntityType.FRAUD_CASE,
                    false
            );
        }

        static EventDraft linkedAlertContext(int sequence, Instant occurredAt, boolean approximateTime) {
            return new EventDraft(
                    sequence,
                    FraudCaseTimelineEventType.LINKED_ALERT_CONTEXT,
                    occurredAt,
                    EvidenceSource.ALERT_SERVICE,
                    EvidenceStatus.AVAILABLE,
                    FraudCaseEvidenceTimelineService.title(FraudCaseTimelineEventType.LINKED_ALERT_CONTEXT),
                    FraudCaseEvidenceTimelineService.description(FraudCaseTimelineEventType.LINKED_ALERT_CONTEXT),
                    FraudCaseTimelineLinkedEntityType.FRAUD_ALERT,
                    approximateTime
            );
        }

        static EventDraft evidenceSnapshot(int sequence, SnapshotEvent snapshot) {
            return new EventDraft(
                    sequence,
                    snapshot.eventType(),
                    snapshot.occurredAt(),
                    snapshot.source(),
                    snapshot.evidenceStatus(),
                    snapshot.title(),
                    snapshot.description(),
                    FraudCaseTimelineLinkedEntityType.EVIDENCE_SNAPSHOT,
                    snapshot.approximateTime()
            );
        }

        static EventDraft legacy(int sequence, Instant occurredAt, boolean approximateTime) {
            return new EventDraft(
                    sequence,
                    FraudCaseTimelineEventType.LEGACY_CONTEXT,
                    occurredAt,
                    EvidenceSource.ALERT_SERVICE,
                    EvidenceStatus.LEGACY,
                    FraudCaseEvidenceTimelineService.title(FraudCaseTimelineEventType.LEGACY_CONTEXT),
                    FraudCaseEvidenceTimelineService.description(FraudCaseTimelineEventType.LEGACY_CONTEXT),
                    FraudCaseTimelineLinkedEntityType.LEGACY_CONTEXT,
                    approximateTime
            );
        }
    }
}
