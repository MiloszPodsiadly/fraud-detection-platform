package com.frauddetection.alert.governance.audit;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GovernanceAdvisoryProjectionService {

    private final GovernanceAdvisoryClient advisoryClient;
    private final GovernanceAdvisoryLifecycleService lifecycleService;
    private final GovernanceAdvisoryAnalyticsService analyticsService;
    private final AlertServiceMetrics metrics;

    public GovernanceAdvisoryProjectionService(
            GovernanceAdvisoryClient advisoryClient,
            GovernanceAdvisoryLifecycleService lifecycleService,
            GovernanceAdvisoryAnalyticsService analyticsService,
            AlertServiceMetrics metrics
    ) {
        this.advisoryClient = advisoryClient;
        this.lifecycleService = lifecycleService;
        this.analyticsService = analyticsService;
        this.metrics = metrics;
    }

    public GovernanceAdvisoryListResponse listAdvisories(
            GovernanceAdvisoryQuery query,
            GovernanceAdvisoryLifecycleStatus lifecycleStatus
    ) {
        GovernanceAdvisoryListResponse response = advisoryClient.listAdvisories(query);
        List<GovernanceAdvisoryEvent> enrichedAll = (response.advisoryEvents() == null ? List.<GovernanceAdvisoryEvent>of() : response.advisoryEvents())
                .stream()
                .map(this::withLifecycleStatus)
                .toList();
        boolean degraded = enrichedAll.stream()
                .anyMatch(event -> event.lifecycleStatus() == GovernanceAdvisoryLifecycleStatus.UNKNOWN);
        List<GovernanceAdvisoryEvent> enriched = enrichedAll.stream()
                .filter(event -> lifecycleStatus == null || event.lifecycleStatus() == lifecycleStatus)
                .toList();
        String status = degraded && "AVAILABLE".equals(response.status()) ? "PARTIAL" : response.status();
        String reasonCode = degraded ? "AUDIT_UNAVAILABLE" : response.reasonCode();
        if (degraded) {
            metrics.recordGovernanceLifecycleDegraded("AUDIT_UNAVAILABLE");
        }
        return new GovernanceAdvisoryListResponse(status, reasonCode, enriched.size(), response.retentionLimit(), enriched);
    }

    public GovernanceAdvisoryEvent getAdvisory(String eventId) {
        return withLifecycleStatus(advisoryClient.getAdvisoryEvent(eventId));
    }

    public GovernanceAdvisoryAnalyticsResponse analytics(int windowDays) {
        return analyticsService.analytics(windowDays);
    }

    private GovernanceAdvisoryEvent withLifecycleStatus(GovernanceAdvisoryEvent event) {
        // Lifecycle is computed per read. It does not modify advisory events or system behavior.
        GovernanceAdvisoryLifecycleStatus status = lifecycleService.lifecycleStatus(event.eventId());
        metrics.recordGovernanceAdvisoryLifecycle(status.name(), event.modelName(), event.modelVersion());
        metrics.recordGovernanceLifecycleStatus(status.name());
        return event.withLifecycleStatus(status);
    }
}
