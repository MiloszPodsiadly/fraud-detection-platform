package com.frauddetection.alert.governance.audit;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GovernanceAdvisoryProjectionService {

    private final GovernanceAdvisoryClient advisoryClient;
    private final GovernanceAdvisoryLifecycleService lifecycleService;
    private final AlertServiceMetrics metrics;

    public GovernanceAdvisoryProjectionService(
            GovernanceAdvisoryClient advisoryClient,
            GovernanceAdvisoryLifecycleService lifecycleService,
            AlertServiceMetrics metrics
    ) {
        this.advisoryClient = advisoryClient;
        this.lifecycleService = lifecycleService;
        this.metrics = metrics;
    }

    public GovernanceAdvisoryListResponse listAdvisories(
            GovernanceAdvisoryQuery query,
            GovernanceAdvisoryLifecycleStatus lifecycleStatus
    ) {
        GovernanceAdvisoryListResponse response = advisoryClient.listAdvisories(query);
        List<GovernanceAdvisoryEvent> enriched = (response.advisoryEvents() == null ? List.<GovernanceAdvisoryEvent>of() : response.advisoryEvents())
                .stream()
                .map(this::withLifecycleStatus)
                .filter(event -> lifecycleStatus == null || event.lifecycleStatus() == lifecycleStatus)
                .toList();
        return new GovernanceAdvisoryListResponse(response.status(), enriched.size(), response.retentionLimit(), enriched);
    }

    public GovernanceAdvisoryEvent getAdvisory(String eventId) {
        return withLifecycleStatus(advisoryClient.getAdvisoryEvent(eventId));
    }

    private GovernanceAdvisoryEvent withLifecycleStatus(GovernanceAdvisoryEvent event) {
        // Lifecycle is computed per read. It does not modify advisory events or system behavior.
        GovernanceAdvisoryLifecycleStatus status = lifecycleService.lifecycleStatus(event.eventId());
        metrics.recordGovernanceAdvisoryLifecycle(status.name(), event.modelName(), event.modelVersion());
        return event.withLifecycleStatus(status);
    }
}
