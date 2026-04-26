package com.frauddetection.alert.governance.audit;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GovernanceAdvisoryAnalyticsServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-26T12:00:00Z");
    private static final Instant FROM = Instant.parse("2026-04-19T12:00:00Z");

    private final GovernanceAuditRepository auditRepository = mock(GovernanceAuditRepository.class);
    private final GovernanceAdvisoryClient advisoryClient = mock(GovernanceAdvisoryClient.class);
    private final GovernanceAdvisoryLifecycleService lifecycleService = mock(GovernanceAdvisoryLifecycleService.class);
    private final AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
    private final GovernanceAdvisoryAnalyticsService service = new GovernanceAdvisoryAnalyticsService(
            auditRepository,
            advisoryClient,
            lifecycleService,
            metrics,
            Clock.fixed(NOW, ZoneOffset.UTC),
            10000
    );

    @Test
    void shouldReturnZerosWhenAuditAndAdvisoryWindowAreEmpty() {
        when(auditRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(eq(FROM), eq(NOW), any(Pageable.class))).thenReturn(List.of());
        when(advisoryClient.listAdvisories(new GovernanceAdvisoryQuery(null, null, 100)))
                .thenReturn(new GovernanceAdvisoryListResponse("AVAILABLE", 0, 200, List.of()));

        GovernanceAdvisoryAnalyticsResponse response = service.analytics(7);

        assertThat(response.status()).isEqualTo("AVAILABLE");
        assertThat(response.totals()).isEqualTo(new GovernanceAdvisoryAnalyticsResponse.Totals(0, 0, 0));
        assertThat(response.decisionDistribution()).containsEntry(GovernanceAuditDecision.ACKNOWLEDGED, 0);
        assertThat(response.lifecycleDistribution()).containsEntry(GovernanceAdvisoryLifecycleStatus.OPEN, 0);
        assertThat(response.reviewTimeliness().status()).isEqualTo("LOW_CONFIDENCE");
        assertThat(response.reviewTimeliness().timeToFirstReviewP50Minutes()).isZero();
        verify(metrics).recordGovernanceAnalyticsRequest(7);
        verify(auditRepository, never()).save(any(GovernanceAuditEventDocument.class));
    }

    @Test
    void shouldComputeDecisionLifecycleAndTimelinessDistributions() {
        when(auditRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(eq(FROM), eq(NOW), any(Pageable.class))).thenReturn(List.of(
                audit("advisory-1", GovernanceAuditDecision.ACKNOWLEDGED, "2026-04-26T00:10:00Z"),
                audit("advisory-2", GovernanceAuditDecision.NEEDS_FOLLOW_UP, "2026-04-26T00:20:00Z"),
                audit("advisory-2", GovernanceAuditDecision.DISMISSED_AS_NOISE, "2026-04-26T00:30:00Z")
        ));
        when(advisoryClient.listAdvisories(new GovernanceAdvisoryQuery(null, null, 100)))
                .thenReturn(new GovernanceAdvisoryListResponse("AVAILABLE", 3, 200, List.of(
                        advisory("advisory-1", "2026-04-26T00:00:00Z"),
                        advisory("advisory-2", "2026-04-26T00:00:00Z"),
                        advisory("advisory-3", "2026-04-26T00:00:00Z")
                )));
        when(lifecycleService.lifecycleStatus("advisory-1")).thenReturn(GovernanceAdvisoryLifecycleStatus.ACKNOWLEDGED);
        when(lifecycleService.lifecycleStatus("advisory-2")).thenReturn(GovernanceAdvisoryLifecycleStatus.DISMISSED_AS_NOISE);
        when(lifecycleService.lifecycleStatus("advisory-3")).thenReturn(GovernanceAdvisoryLifecycleStatus.OPEN);

        GovernanceAdvisoryAnalyticsResponse response = service.analytics(7);

        assertThat(response.totals()).isEqualTo(new GovernanceAdvisoryAnalyticsResponse.Totals(3, 2, 1));
        assertThat(response.decisionDistribution())
                .containsEntry(GovernanceAuditDecision.ACKNOWLEDGED, 1)
                .containsEntry(GovernanceAuditDecision.NEEDS_FOLLOW_UP, 0)
                .containsEntry(GovernanceAuditDecision.DISMISSED_AS_NOISE, 1);
        assertThat(response.lifecycleDistribution())
                .containsEntry(GovernanceAdvisoryLifecycleStatus.OPEN, 1)
                .containsEntry(GovernanceAdvisoryLifecycleStatus.ACKNOWLEDGED, 1)
                .containsEntry(GovernanceAdvisoryLifecycleStatus.DISMISSED_AS_NOISE, 1);
        assertThat(response.reviewTimeliness().status()).isEqualTo("LOW_CONFIDENCE");
        assertThat(response.reviewTimeliness().timeToFirstReviewP50Minutes()).isZero();
        assertThat(response.reviewTimeliness().timeToFirstReviewP95Minutes()).isZero();
    }

    @Test
    void shouldFilterAdvisoriesToBoundedWindow() {
        when(auditRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(eq(FROM), eq(NOW), any(Pageable.class))).thenReturn(List.of());
        when(advisoryClient.listAdvisories(new GovernanceAdvisoryQuery(null, null, 100)))
                .thenReturn(new GovernanceAdvisoryListResponse("AVAILABLE", 2, 200, List.of(
                        advisory("old-advisory", "2026-04-01T00:00:00Z"),
                        advisory("current-advisory", "2026-04-25T00:00:00Z")
                )));

        GovernanceAdvisoryAnalyticsResponse response = service.analytics(7);

        assertThat(response.totals()).isEqualTo(new GovernanceAdvisoryAnalyticsResponse.Totals(1, 0, 1));
    }

    @Test
    void shouldReturnPartialWhenAuditStorageIsUnavailable() {
        when(auditRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(eq(FROM), eq(NOW), any(Pageable.class)))
                .thenThrow(new DataAccessResourceFailureException("mongo down"));
        when(advisoryClient.listAdvisories(new GovernanceAdvisoryQuery(null, null, 100)))
                .thenReturn(new GovernanceAdvisoryListResponse("AVAILABLE", 1, 200, List.of(
                        advisory("advisory-1", "2026-04-25T00:00:00Z")
                )));

        GovernanceAdvisoryAnalyticsResponse response = service.analytics(7);

        assertThat(response.status()).isEqualTo("PARTIAL");
        assertThat(response.totals()).isEqualTo(new GovernanceAdvisoryAnalyticsResponse.Totals(1, 0, 1));
    }

    @Test
    void shouldReturnPartialWhenAdvisoryProjectionIsUnavailable() {
        when(auditRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(eq(FROM), eq(NOW), any(Pageable.class))).thenReturn(List.of(
                audit("advisory-1", GovernanceAuditDecision.NEEDS_FOLLOW_UP, "2026-04-26T00:10:00Z")
        ));
        when(advisoryClient.listAdvisories(new GovernanceAdvisoryQuery(null, null, 100)))
                .thenThrow(new GovernanceAdvisoryLookupUnavailableException());

        GovernanceAdvisoryAnalyticsResponse response = service.analytics(7);

        assertThat(response.status()).isEqualTo("PARTIAL");
        assertThat(response.totals()).isEqualTo(new GovernanceAdvisoryAnalyticsResponse.Totals(0, 0, 0));
        assertThat(response.lifecycleDistribution()).containsEntry(GovernanceAdvisoryLifecycleStatus.NEEDS_FOLLOW_UP, 0);
    }

    @Test
    void shouldSafelyFallbackForCorruptedAuditDecision() {
        GovernanceAuditEventDocument corrupted = audit("advisory-1", null, "2026-04-26T00:10:00Z");
        when(auditRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(eq(FROM), eq(NOW), any(Pageable.class))).thenReturn(List.of(corrupted));
        when(advisoryClient.listAdvisories(new GovernanceAdvisoryQuery(null, null, 100)))
                .thenReturn(new GovernanceAdvisoryListResponse("AVAILABLE", 1, 200, List.of(
                        advisory("advisory-1", "2026-04-26T00:00:00Z")
                )));

        GovernanceAdvisoryAnalyticsResponse response = service.analytics(7);

        assertThat(response.decisionDistribution().values()).allMatch(count -> count == 0);
        assertThat(response.lifecycleDistribution()).containsEntry(GovernanceAdvisoryLifecycleStatus.OPEN, 1);
    }

    @Test
    void shouldReturnUnavailableWhenAuditAndAdvisoryReadsFail() {
        when(auditRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(eq(FROM), eq(NOW), any(Pageable.class)))
                .thenThrow(new DataAccessResourceFailureException("mongo down"));
        when(advisoryClient.listAdvisories(new GovernanceAdvisoryQuery(null, null, 100)))
                .thenThrow(new GovernanceAdvisoryLookupUnavailableException());

        GovernanceAdvisoryAnalyticsResponse response = service.analytics(7);

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        assertThat(response.totals()).isEqualTo(new GovernanceAdvisoryAnalyticsResponse.Totals(0, 0, 0));
    }

    @Test
    void shouldNotDependOnScoringPath() {
        assertThat(GovernanceAdvisoryAnalyticsService.class.getDeclaredFields())
                .extracting(field -> field.getType().getName())
                .noneMatch(typeName -> typeName.toLowerCase().contains("scoring"));
    }

    @Test
    void shouldIgnoreAuditOnlyAdvisoriesForConsistentPopulation() {
        when(auditRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(eq(FROM), eq(NOW), any(Pageable.class))).thenReturn(List.of(
                audit("advisory-1", GovernanceAuditDecision.ACKNOWLEDGED, "2026-04-26T00:10:00Z"),
                audit("audit-only", GovernanceAuditDecision.NEEDS_FOLLOW_UP, "2026-04-26T00:20:00Z")
        ));
        when(advisoryClient.listAdvisories(new GovernanceAdvisoryQuery(null, null, 100)))
                .thenReturn(new GovernanceAdvisoryListResponse("AVAILABLE", 1, 200, List.of(
                        advisory("advisory-1", "2026-04-26T00:00:00Z")
                )));
        when(lifecycleService.lifecycleStatus("advisory-1")).thenReturn(GovernanceAdvisoryLifecycleStatus.ACKNOWLEDGED);

        GovernanceAdvisoryAnalyticsResponse response = service.analytics(7);

        assertThat(response.totals()).isEqualTo(new GovernanceAdvisoryAnalyticsResponse.Totals(1, 1, 0));
        assertThat(response.decisionDistribution())
                .containsEntry(GovernanceAuditDecision.ACKNOWLEDGED, 1)
                .containsEntry(GovernanceAuditDecision.NEEDS_FOLLOW_UP, 0);
        assertThat(response.lifecycleDistribution().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
    }

    @Test
    void shouldReturnAvailableTimelinessOnlyWithEnoughValidSamples() {
        when(auditRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(eq(FROM), eq(NOW), any(Pageable.class))).thenReturn(List.of(
                audit("advisory-1", GovernanceAuditDecision.ACKNOWLEDGED, "2026-04-26T00:10:00Z"),
                audit("advisory-2", GovernanceAuditDecision.ACKNOWLEDGED, "2026-04-26T00:20:00Z"),
                audit("advisory-3", GovernanceAuditDecision.ACKNOWLEDGED, "2026-04-26T00:30:00Z"),
                audit("advisory-4", GovernanceAuditDecision.ACKNOWLEDGED, "2026-04-26T00:40:00Z"),
                audit("advisory-5", GovernanceAuditDecision.ACKNOWLEDGED, "2026-04-26T00:50:00Z"),
                audit("negative-duration", GovernanceAuditDecision.ACKNOWLEDGED, "2026-04-25T23:50:00Z")
        ));
        when(advisoryClient.listAdvisories(new GovernanceAdvisoryQuery(null, null, 100)))
                .thenReturn(new GovernanceAdvisoryListResponse("AVAILABLE", 6, 200, List.of(
                        advisory("advisory-1", "2026-04-26T00:00:00Z"),
                        advisory("advisory-2", "2026-04-26T00:00:00Z"),
                        advisory("advisory-3", "2026-04-26T00:00:00Z"),
                        advisory("advisory-4", "2026-04-26T00:00:00Z"),
                        advisory("advisory-5", "2026-04-26T00:00:00Z"),
                        advisory("negative-duration", "2026-04-26T00:00:00Z")
                )));

        GovernanceAdvisoryAnalyticsResponse response = service.analytics(7);

        assertThat(response.reviewTimeliness().status()).isEqualTo("AVAILABLE");
        assertThat(response.reviewTimeliness().timeToFirstReviewP50Minutes()).isEqualTo(30.0);
        assertThat(response.reviewTimeliness().timeToFirstReviewP95Minutes()).isEqualTo(50.0);
    }

    @Test
    void shouldReturnPartialWhenAuditScanLimitIsExceeded() {
        GovernanceAdvisoryAnalyticsService limitedService = new GovernanceAdvisoryAnalyticsService(
                auditRepository,
                advisoryClient,
                lifecycleService,
                metrics,
                Clock.fixed(NOW, ZoneOffset.UTC),
                1
        );
        when(auditRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(eq(FROM), eq(NOW), any(Pageable.class))).thenReturn(List.of(
                audit("advisory-1", GovernanceAuditDecision.ACKNOWLEDGED, "2026-04-26T00:10:00Z"),
                audit("advisory-2", GovernanceAuditDecision.ACKNOWLEDGED, "2026-04-26T00:20:00Z")
        ));
        when(advisoryClient.listAdvisories(new GovernanceAdvisoryQuery(null, null, 100)))
                .thenReturn(new GovernanceAdvisoryListResponse("AVAILABLE", 1, 200, List.of(
                        advisory("advisory-1", "2026-04-26T00:00:00Z")
                )));

        GovernanceAdvisoryAnalyticsResponse response = limitedService.analytics(7);

        assertThat(response.status()).isEqualTo("PARTIAL");
        assertThat(response.totals()).isEqualTo(new GovernanceAdvisoryAnalyticsResponse.Totals(1, 0, 1));
    }

    @Test
    void shouldSerializeStrictAnalyticsContract() throws Exception {
        when(auditRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(eq(FROM), eq(NOW), any(Pageable.class))).thenReturn(List.of());
        when(advisoryClient.listAdvisories(new GovernanceAdvisoryQuery(null, null, 100)))
                .thenReturn(new GovernanceAdvisoryListResponse("AVAILABLE", 1, 200, List.of(
                        advisory("advisory-1", "2026-04-26T00:00:00Z")
                )));

        GovernanceAdvisoryAnalyticsResponse response = service.analytics(7);

        assertThat(jsonPropertyNames(GovernanceAdvisoryAnalyticsResponse.class)).containsExactlyInAnyOrder(
                "status",
                "window",
                "totals",
                "decision_distribution",
                "lifecycle_distribution",
                "review_timeliness"
        );
        assertThat(jsonPropertyNames(GovernanceAdvisoryAnalyticsResponse.ReviewTimeliness.class))
                .containsExactlyInAnyOrder(
                "status",
                "time_to_first_review_p50_minutes",
                "time_to_first_review_p95_minutes"
        );
        assertThat(response.lifecycleDistribution().values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(response.totals().advisories());
        assertThat(response.totals().advisories()).isGreaterThanOrEqualTo(0);
        assertThat(response.totals().reviewed()).isGreaterThanOrEqualTo(0);
        assertThat(response.totals().open()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void shouldNotExposeAutomationDependencies() {
        assertThat(Arrays.stream(GovernanceAdvisoryAnalyticsService.class.getDeclaredFields())
                .map(field -> field.getType().getName().toLowerCase())
                .toList())
                .noneMatch(typeName -> typeName.contains("retrain"))
                .noneMatch(typeName -> typeName.contains("scoring"))
                .noneMatch(typeName -> typeName.contains("frauddecision"));
    }

    private GovernanceAuditEventDocument audit(String advisoryEventId, GovernanceAuditDecision decision, String createdAt) {
        GovernanceAuditEventDocument document = new GovernanceAuditEventDocument();
        document.setAuditId(advisoryEventId + "-" + createdAt);
        document.setAdvisoryEventId(advisoryEventId);
        document.setDecision(decision);
        document.setCreatedAt(Instant.parse(createdAt));
        return document;
    }

    private GovernanceAdvisoryEvent advisory(String eventId, String createdAt) {
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
                createdAt,
                null
        );
    }

    private List<String> jsonPropertyNames(Class<? extends Record> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(component -> component.getAccessor().getAnnotation(JsonProperty.class))
                .map(JsonProperty::value)
                .toList();
    }
}
