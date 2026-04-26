package com.frauddetection.alert.governance.audit;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
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
    private static final int LOW_CONFIDENCE_SAMPLE_THRESHOLD = 5;

    private final GovernanceAuditRepository auditRepository;
    private final GovernanceAdvisoryClient advisoryClient;
    private final GovernanceAdvisoryLifecycleService lifecycleService;
    private final AlertServiceMetrics metrics;
    private final Clock clock;
    private final int maxAuditEvents;

    @Autowired
    public GovernanceAdvisoryAnalyticsService(
            GovernanceAuditRepository auditRepository,
            GovernanceAdvisoryClient advisoryClient,
            GovernanceAdvisoryLifecycleService lifecycleService,
            AlertServiceMetrics metrics,
            @Value("${app.governance.audit.analytics-max-audit-events:10000}") int maxAuditEvents
    ) {
        this(auditRepository, advisoryClient, lifecycleService, metrics, Clock.systemUTC(), maxAuditEvents);
    }

    GovernanceAdvisoryAnalyticsService(
            GovernanceAuditRepository auditRepository,
            GovernanceAdvisoryClient advisoryClient,
            GovernanceAdvisoryLifecycleService lifecycleService,
            AlertServiceMetrics metrics,
            Clock clock,
            int maxAuditEvents
    ) {
        this.auditRepository = auditRepository;
        this.advisoryClient = advisoryClient;
        this.lifecycleService = lifecycleService;
        this.metrics = metrics;
        this.clock = clock;
        this.maxAuditEvents = maxAuditEvents > 0 ? maxAuditEvents : 10000;
    }

    public GovernanceAdvisoryAnalyticsResponse analytics(int windowDays) {
        // Analytics is observational only.
        // It MUST NOT be used to trigger system actions.
        Instant startedAt = Instant.now(clock);
        metrics.recordGovernanceAnalyticsRequest(windowDays);
        Instant to = Instant.now(clock);
        Instant from = to.minus(Duration.ofDays(windowDays));

        AuditRead auditRead = readAuditEvents(from, to);
        AdvisoryRead advisoryRead = readAdvisories(from, to);
        if (!auditRead.available() && !advisoryRead.available()) {
            return recordOutcome(
                    GovernanceAdvisoryAnalyticsResponse.empty("UNAVAILABLE", from, to, windowDays)
                            .withReason(degradationReason(auditRead, advisoryRead)),
                    startedAt
            );
        }

        Map<String, GovernanceAdvisoryEvent> advisoriesById = advisoryRead.events().stream()
                .filter(event -> event.eventId() != null)
                .collect(Collectors.toMap(
                        GovernanceAdvisoryEvent::eventId,
                        event -> event,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
        Set<String> advisoryIds = new LinkedHashSet<>(advisoriesById.keySet());
        Map<String, List<GovernanceAuditEventDocument>> auditByAdvisory = auditRead.events().stream()
                .filter(event -> event.getAdvisoryEventId() != null && advisoriesById.containsKey(event.getAdvisoryEventId()))
                .collect(Collectors.groupingBy(
                        GovernanceAuditEventDocument::getAdvisoryEventId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        Map<GovernanceAuditDecision, Integer> decisionDistribution = decisionDistribution(advisoryIds, auditByAdvisory);
        Map<GovernanceAdvisoryLifecycleStatus, Integer> lifecycleDistribution = lifecycleDistribution(advisoriesById.values());
        int reviewed = (int) advisoryIds.stream().filter(id -> auditByAdvisory.containsKey(id)).count();
        int open = Math.max(0, advisoryIds.size() - reviewed);
        List<Double> timeToFirstReviewMinutes = timeToFirstReviewMinutes(advisoriesById, auditByAdvisory);

        String status = auditRead.available() && advisoryRead.available() ? "AVAILABLE" : "PARTIAL";
        GovernanceAdvisoryAnalyticsResponse response = new GovernanceAdvisoryAnalyticsResponse(
                status,
                "PARTIAL".equals(status) ? degradationReason(auditRead, advisoryRead) : null,
                new GovernanceAdvisoryAnalyticsResponse.Window(from, to, windowDays),
                new GovernanceAdvisoryAnalyticsResponse.Totals(advisoryIds.size(), reviewed, open),
                decisionDistribution,
                lifecycleDistribution,
                reviewTimeliness(timeToFirstReviewMinutes)
        );
        return recordOutcome(response, startedAt);
    }

    private AuditRead readAuditEvents(Instant from, Instant to) {
        try {
            List<GovernanceAuditEventDocument> events = auditRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(
                    from,
                    to,
                    PageRequest.of(0, maxAuditEvents + 1)
            );
            if (events.size() > maxAuditEvents) {
                return new AuditRead(false, "AUDIT_LIMIT_EXCEEDED", List.of());
            }
            return new AuditRead(true, null, events);
        } catch (DataAccessException exception) {
            return new AuditRead(false, "AUDIT_UNAVAILABLE", List.of());
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
            return new AdvisoryRead(true, null, events);
        } catch (RuntimeException exception) {
            return new AdvisoryRead(false, "ADVISORY_UNAVAILABLE", List.of());
        }
    }

    private Map<GovernanceAuditDecision, Integer> decisionDistribution(
            Set<String> advisoryIds,
            Map<String, List<GovernanceAuditEventDocument>> auditByAdvisory
    ) {
        EnumMap<GovernanceAuditDecision, Integer> distribution = new EnumMap<>(GovernanceAdvisoryAnalyticsResponse.emptyDecisionDistribution());
        for (String advisoryId : advisoryIds) {
            latestDecision(auditByAdvisory.getOrDefault(advisoryId, List.of()))
                    .ifPresent(decision -> distribution.computeIfPresent(decision, (ignored, count) -> count + 1));
        }
        return distribution;
    }

    private Map<GovernanceAdvisoryLifecycleStatus, Integer> lifecycleDistribution(
            Iterable<GovernanceAdvisoryEvent> advisories
    ) {
        EnumMap<GovernanceAdvisoryLifecycleStatus, Integer> distribution =
                new EnumMap<>(GovernanceAdvisoryAnalyticsResponse.emptyLifecycleDistribution());
        for (GovernanceAdvisoryEvent advisory : advisories) {
            GovernanceAdvisoryLifecycleStatus status = lifecycleService.lifecycleStatus(advisory.eventId());
            if (status == null) {
                status = GovernanceAdvisoryLifecycleStatus.OPEN;
            }
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

    private java.util.Optional<GovernanceAuditDecision> latestDecision(List<GovernanceAuditEventDocument> events) {
        return events.stream()
                .max(Comparator.comparing(GovernanceAuditEventDocument::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(GovernanceAuditEventDocument::getDecision)
                .filter(decision -> decision != null);
    }

    private GovernanceAdvisoryAnalyticsResponse.ReviewTimeliness reviewTimeliness(List<Double> values) {
        if (values.size() < LOW_CONFIDENCE_SAMPLE_THRESHOLD) {
            return new GovernanceAdvisoryAnalyticsResponse.ReviewTimeliness("LOW_CONFIDENCE", 0.0, 0.0);
        }
        return new GovernanceAdvisoryAnalyticsResponse.ReviewTimeliness(
                "AVAILABLE",
                percentile(values, 50),
                percentile(values, 95)
        );
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

    private GovernanceAdvisoryAnalyticsResponse recordOutcome(GovernanceAdvisoryAnalyticsResponse response, Instant startedAt) {
        metrics.recordGovernanceAnalyticsOutcome(response.status(), Duration.between(startedAt, Instant.now(clock)));
        return response;
    }

    private String degradationReason(AuditRead auditRead, AdvisoryRead advisoryRead) {
        if (!auditRead.available()) {
            return auditRead.reason();
        }
        if (!advisoryRead.available()) {
            return advisoryRead.reason();
        }
        return null;
    }

    private record AuditRead(boolean available, String reason, List<GovernanceAuditEventDocument> events) {
    }

    private record AdvisoryRead(boolean available, String reason, List<GovernanceAdvisoryEvent> events) {
    }
}
