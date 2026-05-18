package com.frauddetection.alert.evidence;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.evidence.ScoringEvidenceItem;
import com.frauddetection.common.events.evidence.ScoringEvidenceSeverity;
import com.frauddetection.common.events.evidence.ScoringEvidenceSource;
import com.frauddetection.common.events.evidence.ScoringEvidenceStatus;
import com.frauddetection.common.events.evidence.ScoringEvidenceType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AlertEvidenceSnapshotProjectionServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-05-18T09:59:00Z");
    private static final Instant OBSERVED_AT = Instant.parse("2026-05-18T10:00:00Z");
    private static final Instant PROJECTED_AT = Instant.parse("2026-05-18T10:01:00Z");

    @Test
    void scoringEvidenceProjectsToAlertEvidenceSnapshot() {
        List<EvidenceSnapshotItem> snapshot = service(50).project(event(RiskLevel.HIGH, true, List.of(available())));

        assertThat(snapshot).singleElement().satisfies(item -> {
            assertThat(item.sourceEventId()).isEqualTo("event-1");
            assertThat(item.transactionId()).isEqualTo("txn-1");
            assertThat(item.correlationId()).isEqualTo("corr-1");
            assertThat(item.status()).isEqualTo(EvidenceStatus.AVAILABLE);
            assertThat(item.observedAt()).isEqualTo(OBSERVED_AT);
            assertThat(item.projectedAt()).isEqualTo(PROJECTED_AT);
            assertThat(item.attributes()).containsEntry("evidenceProjectionState", EvidenceProjectionState.PROJECTED.name());
        });
    }

    @Test
    void scoringEvidenceStatusesArePreserved() {
        assertThat(first(project(available())).status()).isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(first(project(diagnostic(ScoringEvidenceStatus.PARTIAL))).status()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(first(project(diagnostic(ScoringEvidenceStatus.UNAVAILABLE))).status()).isEqualTo(EvidenceStatus.UNAVAILABLE);
        assertThat(first(project(diagnostic(ScoringEvidenceStatus.ERROR))).status()).isEqualTo(EvidenceStatus.ERROR);
        assertThat(first(project(diagnostic(ScoringEvidenceStatus.LEGACY))).status()).isEqualTo(EvidenceStatus.LEGACY);
    }

    @Test
    void legacyStatusUsesLegacyProjectedState() {
        assertThat(first(project(diagnostic(ScoringEvidenceStatus.LEGACY))).attributes())
                .containsEntry("evidenceProjectionState", EvidenceProjectionState.LEGACY_PROJECTED.name());
    }

    @Test
    void upstreamErrorStatusUsesErrorProjectedState() {
        assertThat(first(project(diagnostic(ScoringEvidenceStatus.ERROR))).attributes())
                .containsEntry("evidenceProjectionState", EvidenceProjectionState.ERROR_PROJECTED.name());
    }

    @Test
    void missingLineageDoesNotCreateAvailableSnapshot() {
        assertThat(service(50).project(event(null, "txn-1", "corr-1", RiskLevel.HIGH, true, List.of(available()))))
                .singleElement().satisfies(item -> {
                    assertThat(item.status()).isEqualTo(EvidenceStatus.PARTIAL);
                    assertThat(item.evidenceType()).isEqualTo(EvidenceType.DIAGNOSTIC);
                    assertThat(item.attributes()).containsEntry("evidenceProjectionState", EvidenceProjectionState.PARTIAL_MISSING_SOURCE_EVENT_ID.name());
                });
        assertThat(service(50).project(event("event-1", null, "corr-1", RiskLevel.HIGH, true, List.of(available()))))
                .noneMatch(item -> item.status() == EvidenceStatus.AVAILABLE);
        assertThat(service(50).project(event("event-1", "txn-1", null, RiskLevel.HIGH, true, List.of(available()))))
                .noneMatch(item -> item.status() == EvidenceStatus.AVAILABLE);
    }

    @Test
    void multipleMissingLineageFieldsCreateCombinedDiagnostic() {
        List<EvidenceSnapshotItem> snapshot = service(50).project(event(null, null, " ", RiskLevel.HIGH, true, List.of(available())));

        assertThat(snapshot).singleElement().satisfies(item ->
                assertThat(item.attributes()).containsEntry("evidenceProjectionState", EvidenceProjectionState.PARTIAL_MISSING_REQUIRED_LINEAGE.name()));
    }

    @Test
    void emptyScoringEvidenceSemanticsAreExplicit() {
        assertThat(service(50).project(event(RiskLevel.HIGH, true, List.of())))
                .singleElement().satisfies(item -> {
                    assertThat(item.status()).isEqualTo(EvidenceStatus.PARTIAL);
                    assertThat(item.evidenceType()).isEqualTo(EvidenceType.DIAGNOSTIC);
                    assertThat(item.attributes()).containsEntry("evidenceProjectionState", EvidenceProjectionState.PARTIAL_EMPTY_SCORING_EVIDENCE.name());
                });
        assertThat(service(50).project(event(RiskLevel.CRITICAL, false, List.of())))
                .singleElement().satisfies(item -> assertThat(item.status()).isEqualTo(EvidenceStatus.PARTIAL));
        assertThat(service(50).project(event(RiskLevel.LOW, false, List.of()))).isEmpty();
    }

    @Test
    void evidenceSnapshotIsBoundedWithExplicitTruncationDiagnostic() {
        List<ScoringEvidenceItem> evidence = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            evidence.add(available("COUNTRY_MISMATCH_" + index));
        }

        List<EvidenceSnapshotItem> snapshot = service(3).project(event(RiskLevel.HIGH, true, evidence));

        assertThat(snapshot).hasSize(3);
        assertThat(snapshot.getLast()).satisfies(item -> {
            assertThat(item.evidenceType()).isEqualTo(EvidenceType.DIAGNOSTIC);
            assertThat(item.status()).isEqualTo(EvidenceStatus.PARTIAL);
            assertThat(item.attributes())
                    .containsEntry("evidenceProjectionState", EvidenceProjectionState.PARTIAL_TRUNCATED.name())
                    .containsEntry("originalEvidenceCount", 5)
                    .containsEntry("retainedEvidenceCount", 2)
                    .containsEntry("truncatedEvidenceCount", 3)
                    .containsEntry("maxEvidenceSnapshotItems", 3);
        });
    }

    @Test
    void maxTwoRetainsOneItemAndOneDiagnostic() {
        List<EvidenceSnapshotItem> snapshot = service(2).project(event(RiskLevel.HIGH, true, List.of(
                available("A"),
                available("B"),
                available("C")
        )));

        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.getFirst().status()).isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(snapshot.getLast()).satisfies(item -> {
            assertThat(item.evidenceType()).isEqualTo(EvidenceType.DIAGNOSTIC);
            assertThat(item.attributes())
                    .containsEntry("evidenceProjectionState", EvidenceProjectionState.PARTIAL_TRUNCATED.name())
                    .containsEntry("originalEvidenceCount", 3)
                    .containsEntry("retainedEvidenceCount", 1)
                    .containsEntry("truncatedEvidenceCount", 2);
        });
    }

    @Test
    void oversizedInputDoesNotMaterializeMoreThanLimit() {
        List<ScoringEvidenceItem> evidence = new ArrayList<>();
        for (int index = 0; index < 1000; index++) {
            evidence.add(available("OVERSIZED_" + index));
        }

        List<EvidenceSnapshotItem> snapshot = service(50).project(event(RiskLevel.HIGH, true, evidence));

        assertThat(snapshot).hasSize(50);
        assertThat(snapshot.getLast()).satisfies(item -> assertThat(item.attributes())
                .containsEntry("evidenceProjectionState", EvidenceProjectionState.PARTIAL_TRUNCATED.name())
                .containsEntry("originalEvidenceCount", 1000)
                .containsEntry("retainedEvidenceCount", 49)
                .containsEntry("truncatedEvidenceCount", 951));
    }

    @Test
    void corruptScoringEvidenceCreatesErrorDiagnostic() {
        ScoringEvidenceSnapshotMapper mapper = new ScoringEvidenceSnapshotMapper() {
            @Override
            public EvidenceStatus mapStatus(ScoringEvidenceStatus status) {
                throw new IllegalArgumentException("raw detail must not be stored");
            }
        };

        List<EvidenceSnapshotItem> snapshot = service(mapper, 50, metrics()).project(event(RiskLevel.HIGH, true, List.of(available())));

        assertThat(snapshot).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(EvidenceStatus.ERROR);
            assertThat(item.evidenceType()).isEqualTo(EvidenceType.DIAGNOSTIC);
            assertThat(item.reasonCode()).isNull();
            assertThat(item.attributes())
                    .containsEntry("projectionError", true)
                    .containsEntry("projectionErrorType", "IllegalArgumentException")
                    .containsEntry("invalidScoringEvidenceIndex", 0)
                    .containsEntry("evidenceProjectionState", EvidenceProjectionState.ERROR_PROJECTION_FAILED.name());
            assertThat(item.attributes()).doesNotContainValue("raw detail must not be stored");
        });
        assertThat(snapshot).noneMatch(item -> item.status() == EvidenceStatus.AVAILABLE);
    }

    @Test
    void projectOrDiagnosticReturnsErrorDiagnosticWhenProjectionThrows() {
        AlertEvidenceSnapshotProjectionService service = new AlertEvidenceSnapshotProjectionService(
                new ScoringEvidenceSnapshotMapper(),
                new AlertEvidenceSnapshotProperties(50),
                metrics(),
                Clock.fixed(PROJECTED_AT, ZoneOffset.UTC)
        ) {
            @Override
            public List<EvidenceSnapshotItem> project(TransactionScoredEvent event) {
                throw new IllegalStateException("raw exception message");
            }
        };

        List<EvidenceSnapshotItem> snapshot = service.projectOrDiagnostic(event(RiskLevel.HIGH, true, List.of(available())));

        assertThat(snapshot).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(EvidenceStatus.ERROR);
            assertThat(item.evidenceType()).isEqualTo(EvidenceType.DIAGNOSTIC);
            assertThat(item.attributes())
                    .containsEntry("projectionError", true)
                    .containsEntry("projectionErrorType", "IllegalStateException")
                    .containsEntry("evidenceProjectionState", EvidenceProjectionState.ERROR_PROJECTION_FAILED.name());
            assertThat(item.attributes()).doesNotContainValue("raw exception message");
        });
    }

    @Test
    void projectionDoesNotDuplicateTypedLineageFieldsIntoAttributes() {
        EvidenceSnapshotItem item = first(project(available()));

        assertThat(item.scoringStrategy()).isEqualTo("RULE_BASED");
        assertThat(item.modelName()).isEqualTo("rule-based");
        assertThat(item.modelVersion()).isEqualTo("v1");
        assertThat(item.attributes()).doesNotContainKeys("scoringStrategy", "modelName", "modelVersion");
    }

    @Test
    void projectionErrorIsRecordedWithLowCardinalitySignal() {
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        ScoringEvidenceSnapshotMapper mapper = new ScoringEvidenceSnapshotMapper() {
            @Override
            public EvidenceStatus mapStatus(ScoringEvidenceStatus status) {
                throw new IllegalArgumentException("raw detail must not be stored");
            }
        };

        service(mapper, 50, metrics).project(event(RiskLevel.HIGH, true, List.of(available())));

        verify(metrics).recordEvidenceSnapshotProjectionError();
    }

    @Test
    void truncationIsRecorded() {
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);

        service(new ScoringEvidenceSnapshotMapper(), 2, metrics).project(event(RiskLevel.HIGH, true, List.of(
                available("A"),
                available("B"),
                available("C")
        )));

        verify(metrics).recordEvidenceSnapshotProjectionTruncated();
    }

    @Test
    void diagnosticProjectionIsRecorded() {
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);

        service(new ScoringEvidenceSnapshotMapper(), 50, metrics).project(event(RiskLevel.HIGH, true, List.of()));

        verify(metrics).recordEvidenceSnapshotProjectionDiagnostic(EvidenceProjectionState.PARTIAL_EMPTY_SCORING_EVIDENCE);
    }

    @Test
    void snapshotRejectsUnsafeAttributes() {
        assertThatCode(() -> service(50).project(event(RiskLevel.HIGH, true, List.of(
                new ScoringEvidenceItem(
                        "evidence-unsafe",
                        null,
                        ScoringEvidenceType.DIAGNOSTIC,
                        ScoringEvidenceSource.ML_MODEL,
                        ScoringEvidenceStatus.PARTIAL,
                        ScoringEvidenceSeverity.LOW,
                        "Diagnostic",
                        "Diagnostic",
                        null,
                        null,
                        Map.of(
                                "diagnostic", true,
                                "supportedEvidenceCreated", false,
                                "reasonCodeApplicable", false,
                                "futureHarmlessAttribute", List.of("safe", 1, true)
                        ),
                        OBSERVED_AT
                )
        )))).doesNotThrowAnyException();
    }

    private List<EvidenceSnapshotItem> project(ScoringEvidenceItem item) {
        return service(50).project(event(RiskLevel.HIGH, true, List.of(item)));
    }

    private EvidenceSnapshotItem first(List<EvidenceSnapshotItem> items) {
        return items.getFirst();
    }

    private AlertEvidenceSnapshotProjectionService service(int maxItems) {
        return service(new ScoringEvidenceSnapshotMapper(), maxItems, metrics());
    }

    private AlertEvidenceSnapshotProjectionService service(
            ScoringEvidenceSnapshotMapper mapper,
            int maxItems,
            AlertServiceMetrics metrics
    ) {
        return new AlertEvidenceSnapshotProjectionService(
                mapper,
                new AlertEvidenceSnapshotProperties(maxItems),
                metrics,
                Clock.fixed(PROJECTED_AT, ZoneOffset.UTC)
        );
    }

    private AlertServiceMetrics metrics() {
        return new AlertServiceMetrics(new SimpleMeterRegistry());
    }

    private TransactionScoredEvent event(RiskLevel riskLevel, boolean alertRecommended, List<ScoringEvidenceItem> scoringEvidence) {
        return event("event-1", "txn-1", "corr-1", riskLevel, alertRecommended, scoringEvidence);
    }

    private TransactionScoredEvent event(
            String eventId,
            String transactionId,
            String correlationId,
            RiskLevel riskLevel,
            boolean alertRecommended,
            List<ScoringEvidenceItem> scoringEvidence
    ) {
        return new TransactionScoredEvent(
                eventId,
                transactionId,
                correlationId,
                "customer-1",
                "account-1",
                CREATED_AT,
                CREATED_AT,
                null,
                null,
                null,
                null,
                null,
                0.91d,
                riskLevel,
                "RULE_BASED",
                "rule-based",
                "v1",
                OBSERVED_AT,
                List.of("COUNTRY_MISMATCH"),
                Map.of(),
                Map.of(),
                alertRecommended,
                scoringEvidence
        );
    }

    private ScoringEvidenceItem available() {
        return available("COUNTRY_MISMATCH");
    }

    private ScoringEvidenceItem available(String evidenceIdSuffix) {
        return new ScoringEvidenceItem(
                "evidence-" + evidenceIdSuffix,
                "COUNTRY_MISMATCH",
                ScoringEvidenceType.GEO_SIGNAL,
                ScoringEvidenceSource.RULE_BASED_SCORING,
                ScoringEvidenceStatus.AVAILABLE,
                ScoringEvidenceSeverity.HIGH,
                "Country mismatch",
                "Transaction geography differed from expected context.",
                null,
                null,
                Map.of(),
                OBSERVED_AT
        );
    }

    private ScoringEvidenceItem diagnostic(ScoringEvidenceStatus status) {
        return new ScoringEvidenceItem(
                "diagnostic-" + status.name(),
                null,
                ScoringEvidenceType.DIAGNOSTIC,
                ScoringEvidenceSource.ML_RUNTIME,
                status,
                ScoringEvidenceSeverity.LOW,
                "Diagnostic",
                "Diagnostic context",
                null,
                null,
                Map.of(
                        "diagnostic", true,
                        "supportedEvidenceCreated", false,
                        "reasonCodeApplicable", false
                ),
                OBSERVED_AT
        );
    }
}
