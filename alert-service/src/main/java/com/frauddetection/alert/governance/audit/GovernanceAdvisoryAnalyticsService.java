package com.frauddetection.alert.governance.audit;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GovernanceAdvisoryAnalyticsService {

    private static final int ADVISORY_WINDOW_LIMIT = 100;

    private final GovernanceAuditRepository auditRepository;
    private final GovernanceAdvisoryClient advisoryClient;
    private final AlertServiceMetrics metrics;
    private final Clock clock;

    @Autowired
    public GovernanceAdvisoryAnalyticsService(
            GovernanceAuditRepository auditRepository,
            GovernanceAdvisoryClient advisoryClient,
            AlertServiceMetrics metrics
    ) {
        this(auditRepository, advisoryClient, metrics, Clock.systemUTC());
    }

    GovernanceAdvisoryAnalyticsService(
            GovernanceAuditRepository auditRepository,
            GovernanceAdvisoryClient advisoryClient,
            AlertServiceMetrics metrics,
            Clock clock
    ) {
        this.auditRepository = auditRepository;
        this.advisoryClient = advisoryClient;
        this.metrics = metrics;
        this.clock = clock;
    }

    public GovernanceAdvisoryAnalyticsResponse analytics(int windowDays) {
        metrics.recordGovernanceAnalyticsRequest(windowDays);
        Instant to = Instant.now(clock);
        Instant from = to.minus(Duration.ofDays(windowDays));

        AuditRead auditRead = readAuditEvents(from, to);
        AdvisoryRead advisoryRead = readAdvisories(from, to);
        if (!auditRead.available() && !advisoryRead.available()) {
            return GovernanceAdvisoryAnalyticsResponse.empty("UNAVAILABLE", from, to, windowDays);
        }

        Map<String, List<GovernanceAuditEventDocument>> auditByAdvisory = auditRead.events().stream()
                .filter(event -> event.getAdvisoryEventId() != null)
                .collect(Collectors.groupingBy(
                        GovernanceAuditEventDocument::getAdvisoryEventId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        Map<String, GovernanceAdvisoryEvent> advisoriesById = advisoryRead.events().stream()
                .filter(event -> event.eventId() != null)
                .collect(Collectors.toMap(
                        GovernanceAdvisoryEvent::eventId,
                        event -> event,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
        Set<String> advisoryIds = new LinkedHashSet<>(advisoriesById.keySet());
        advisoryIds.addAll(auditByAdvisory.keySet());

        Map<GovernanceAuditDecision, Integer> decisionDistribution = decisionDistribution(auditRead.events());
        Map<GovernanceAdvisoryLifecycleStatus, Integer> lifecycleDistribution = lifecycleDistribution(advisoryIds, auditByAdvisory);
        int reviewed = (int) advisoryIds.stream().filter(id -> auditByAdvisory.containsKey(id)).count();
        int open = Math.max(0, advisoryIds.size() - reviewed);
        List<Double> timeToFirstReviewMinutes = timeToFirstReviewMinutes(advisoriesById, auditByAdvisory);

        String status = auditRead.available() && advisoryRead.available() ? "AVAILABLE" : "PARTIAL";
        return new GovernanceAdvisoryAnalyticsResponse(
                status,
                new GovernanceAdvisoryAnalyticsResponse.Window(from, to, windowDays),
                new GovernanceAdvisoryAnalyticsResponse.Totals(advisoryIds.size(), reviewed, open),
                decisionDistribution,
                lifecycleDistribution,
                new GovernanceAdvisoryAnalyticsResponse.ReviewTimeliness(
                        percentile(timeToFirstReviewMinutes, 50),
                        percentile(timeToFirstReviewMinutes, 95)
                )
        );
    }

    private AuditRead readAuditEvents(Instant from, Instant to) {
        try {
            return new AuditRead(true, auditRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(from, to));
        } catch (DataAccessException exception) {
            return new AuditRead(false, List.of());
        }
    }

    private AdvisoryRead readAdvisories(Instant from, Instant to) {
        try {
            GovernanceAdvisoryListResponse response = advisoryClient.listAdvisories(
                    new GovernanceAdvisoryQuery(null, null, ADVISORY_WINDOW_LIMIT)
            );
            List<GovernanceAdvisoryEvent> events = response.advisoryEvents() == null
                    ? List.of()
                    : response.advisoryEvents().stream()
                    .filter(event -> isWithinWindow(event.createdAt(), from, to))
                    .toList();
            return new AdvisoryRead(true, events);
        } catch (RuntimeException exception) {
            return new AdvisoryRead(false, List.of());
        }
    }

    private Map<GovernanceAuditDecision, Integer> decisionDistribution(List<GovernanceAuditEventDocument> events) {
        EnumMap<GovernanceAuditDecision, Integer> distribution = new EnumMap<>(GovernanceAdvisoryAnalyticsResponse.emptyDecisionDistribution());
        for (GovernanceAuditEventDocument event : events) {
            if (event.getDecision() != null) {
                distribution.computeIfPresent(event.getDecision(), (ignored, count) -> count + 1);
            }
        }
        return distribution;
    }

    private Map<GovernanceAdvisoryLifecycleStatus, Integer> lifecycleDistribution(
            Set<String> advisoryIds,
            Map<String, List<GovernanceAuditEventDocument>> auditByAdvisory
    ) {
        EnumMap<GovernanceAdvisoryLifecycleStatus, Integer> distribution =
                new EnumMap<>(GovernanceAdvisoryAnalyticsResponse.emptyLifecycleDistribution());
        for (String advisoryId : advisoryIds) {
            GovernanceAdvisoryLifecycleStatus status = auditByAdvisory.getOrDefault(advisoryId, List.of()).stream()
                    .max(Comparator.comparing(GovernanceAuditEventDocument::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                    .map(GovernanceAuditEventDocument::getDecision)
                    .map(GovernanceAdvisoryLifecycleStatus::fromLatestDecision)
                    .orElse(GovernanceAdvisoryLifecycleStatus.OPEN);
            distribution.computeIfPresent(status, (ignored, count) -> count + 1);
        }
        return distribution;
    }

    private List<Double> timeToFirstReviewMinutes(
            Map<String, GovernanceAdvisoryEvent> advisoriesById,
            Map<String, List<GovernanceAuditEventDocument>> auditByAdvisory
    ) {
        List<Double> minutes = new ArrayList<>();
        for (Map.Entry<String, GovernanceAdvisoryEvent> entry : advisoriesById.entrySet()) {
            Instant advisoryCreatedAt = parseInstant(entry.getValue().createdAt());
            if (advisoryCreatedAt == null) {
                continue;
            }
            auditByAdvisory.getOrDefault(entry.getKey(), List.of()).stream()
                    .map(GovernanceAuditEventDocument::getCreatedAt)
                    .filter(createdAt -> createdAt != null && !createdAt.isBefore(advisoryCreatedAt))
                    .min(Comparator.naturalOrder())
                    .ifPresent(firstReview -> minutes.add(Duration.between(advisoryCreatedAt, firstReview).toSeconds() / 60.0));
        }
        return minutes;
    }

    private boolean isWithinWindow(String timestamp, Instant from, Instant to) {
        Instant parsed = parseInstant(timestamp);
        return parsed != null && !parsed.isBefore(from) && !parsed.isAfter(to);
    }

    private Instant parseInstant(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(timestamp);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private double percentile(List<Double> values, int percentile) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = values.stream().sorted().toList();
        int index = Math.max(0, (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1);
        return sorted.get(Math.min(index, sorted.size() - 1));
    }

    private record AuditRead(boolean available, List<GovernanceAuditEventDocument> events) {
    }

    private record AdvisoryRead(boolean available, List<GovernanceAdvisoryEvent> events) {
    }
}
