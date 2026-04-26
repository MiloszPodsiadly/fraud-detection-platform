package com.frauddetection.alert.governance.audit;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GovernanceAdvisoryProjectionServiceTest {

    private final GovernanceAdvisoryClient advisoryClient = mock(GovernanceAdvisoryClient.class);
    private final GovernanceAdvisoryLifecycleService lifecycleService = mock(GovernanceAdvisoryLifecycleService.class);
    private final AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
    private final GovernanceAdvisoryProjectionService service = new GovernanceAdvisoryProjectionService(
            advisoryClient,
            lifecycleService,
            metrics
    );

    @Test
    void shouldAddLifecycleStatusWithoutChangingAdvisoryFields() {
        GovernanceAdvisoryEvent event = advisoryEvent("advisory-1");
        when(advisoryClient.listAdvisories(new GovernanceAdvisoryQuery("HIGH", "2026-04-21.trained.v1", 25)))
                .thenReturn(new GovernanceAdvisoryListResponse("AVAILABLE", 1, 200, List.of(event)));
        when(lifecycleService.lifecycleStatus("advisory-1")).thenReturn(GovernanceAdvisoryLifecycleStatus.NEEDS_FOLLOW_UP);

        GovernanceAdvisoryListResponse response = service.listAdvisories(
                new GovernanceAdvisoryQuery("HIGH", "2026-04-21.trained.v1", 25),
                null
        );

        GovernanceAdvisoryEvent enriched = response.advisoryEvents().getFirst();
        assertThat(enriched.lifecycleStatus()).isEqualTo(GovernanceAdvisoryLifecycleStatus.NEEDS_FOLLOW_UP);
        assertThat(enriched.eventId()).isEqualTo(event.eventId());
        assertThat(enriched.driftStatus()).isEqualTo(event.driftStatus());
        assertThat(enriched.confidence()).isEqualTo(event.confidence());
        assertThat(enriched.recommendedActions()).isEqualTo(event.recommendedActions());
        verify(metrics).recordGovernanceAdvisoryLifecycle(
                "NEEDS_FOLLOW_UP",
                "python-logistic-fraud-model",
                "2026-04-21.trained.v1"
        );
    }

    @Test
    void shouldFilterByDerivedLifecycleStatusOnlyAfterAuditProjection() {
        when(advisoryClient.listAdvisories(new GovernanceAdvisoryQuery(null, null, 25)))
                .thenReturn(new GovernanceAdvisoryListResponse(
                        "AVAILABLE",
                        2,
                        200,
                        List.of(advisoryEvent("advisory-1"), advisoryEvent("advisory-2"))
                ));
        when(lifecycleService.lifecycleStatus("advisory-1")).thenReturn(GovernanceAdvisoryLifecycleStatus.OPEN);
        when(lifecycleService.lifecycleStatus("advisory-2")).thenReturn(GovernanceAdvisoryLifecycleStatus.ACKNOWLEDGED);

        GovernanceAdvisoryListResponse response = service.listAdvisories(
                new GovernanceAdvisoryQuery(null, null, 25),
                GovernanceAdvisoryLifecycleStatus.ACKNOWLEDGED
        );

        assertThat(response.count()).isEqualTo(1);
        assertThat(response.advisoryEvents()).extracting(GovernanceAdvisoryEvent::eventId).containsExactly("advisory-2");
    }

    @Test
    void shouldKeepAdvisoryPayloadStableAcrossLifecycleStates() {
        GovernanceAdvisoryEvent event = advisoryEvent("advisory-1");

        for (GovernanceAdvisoryLifecycleStatus status : GovernanceAdvisoryLifecycleStatus.values()) {
            when(advisoryClient.listAdvisories(new GovernanceAdvisoryQuery(null, null, 25)))
                    .thenReturn(new GovernanceAdvisoryListResponse("AVAILABLE", 1, 200, List.of(event)));
            when(lifecycleService.lifecycleStatus("advisory-1")).thenReturn(status);

            GovernanceAdvisoryEvent enriched = service.listAdvisories(
                    new GovernanceAdvisoryQuery(null, null, 25),
                    null
            ).advisoryEvents().getFirst();

            assertThat(enriched.lifecycleStatus()).isEqualTo(status);
            assertThat(enriched.driftStatus()).isEqualTo(event.driftStatus());
            assertThat(enriched.confidence()).isEqualTo(event.confidence());
            assertThat(enriched.recommendedActions()).isEqualTo(event.recommendedActions());
        }
    }

    @Test
    void shouldNotResolveLifecycleWhenAdvisoryIsMissing() {
        when(advisoryClient.getAdvisoryEvent("missing-advisory"))
                .thenThrow(new GovernanceAdvisoryNotFoundException("missing-advisory"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.getAdvisory("missing-advisory"))
                .isInstanceOf(GovernanceAdvisoryNotFoundException.class);

        verifyNoInteractions(lifecycleService);
        verifyNoInteractions(metrics);
    }

    @Test
    void shouldHaveNoScoringPathDependency() {
        assertThat(GovernanceAdvisoryProjectionService.class.getDeclaredFields())
                .extracting(field -> field.getType().getName())
                .noneMatch(typeName -> typeName.toLowerCase().contains("scoring"));
    }

    private GovernanceAdvisoryEvent advisoryEvent(String eventId) {
        return new GovernanceAdvisoryEvent(
                eventId,
                "GOVERNANCE_DRIFT_ADVISORY",
                "HIGH",
                "DRIFT",
                "HIGH",
                "SUFFICIENT_DATA",
                "python-logistic-fraud-model",
                "2026-04-21.trained.v1",
                Map.of("current_model_version", "2026-04-21.trained.v1"),
                List.of("KEEP_SCORING_UNCHANGED"),
                "score p95 increased compared to reference profile",
                "2026-04-26T00:02:00+00:00",
                null
        );
    }
}
