package com.frauddetection.alert.evidence;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.evidence.ScoringEvidenceItem;
import com.frauddetection.common.events.evidence.ScoringEvidenceSeverity;
import com.frauddetection.common.events.evidence.ScoringEvidenceSource;
import com.frauddetection.common.events.evidence.ScoringEvidenceStatus;
import com.frauddetection.common.events.evidence.ScoringEvidenceType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
        return new AlertEvidenceSnapshotProjectionService(
                new ScoringEvidenceSnapshotMapper(),
                new AlertEvidenceSnapshotProperties(maxItems),
                Clock.fixed(PROJECTED_AT, ZoneOffset.UTC)
        );
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
