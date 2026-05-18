package com.frauddetection.alert.evidence;

import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.frauddetection.alert.evidence.EvidenceProjectionTestSupport.scoredEvent;
import static org.assertj.core.api.Assertions.assertThat;

class EvidenceDiagnosticSemanticsTest {

    private final EvidenceProjectionService service = new EvidenceProjectionService(
            new ReasonCodeEvidenceTypeMapper(),
            Clock.fixed(Instant.parse("2026-05-18T11:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void diagnosticEvidenceMarksReasonCodeNotApplicable() {
        EvidenceDocument diagnostic = service.projectFromScoredEvent(scoredEvent(
                "event-1",
                "txn-1",
                null,
                RiskLevel.HIGH,
                List.of("COUNTRY_MISMATCH")
        )).getFirst();

        assertThat(diagnostic.getReasonCode()).isNull();
        assertThat(diagnostic.getAttributes())
                .containsEntry("diagnostic", true)
                .containsEntry("supportedEvidenceCreated", false)
                .containsEntry("reasonCodeApplicable", false);
    }

    @Test
    void unsupportedDiagnosticDoesNotLookLikeSupportedReasonEvidence() {
        EvidenceDocument diagnostic = service.projectFromScoredEvent(scoredEvent(
                RiskLevel.HIGH,
                List.of("legacy-risk-marker")
        )).getFirst();

        assertThat(diagnostic.getEvidenceType()).isEqualTo(EvidenceType.DIAGNOSTIC);
        assertThat(diagnostic.getReasonCode()).isNull();
        assertThat(diagnostic.getStatus()).isNotEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(diagnostic.getAttributes())
                .containsEntry("reasonCodeApplicable", false)
                .containsEntry("supportedEvidenceCreated", false)
                .containsEntry("reasonCodeParseStatus", "UNSUPPORTED")
                .containsEntry("unsupportedReasonCodePresent", true);
    }

    @Test
    void missingReasonCodesDiagnosticMarksReasonCodeNotApplicable() {
        EvidenceDocument diagnostic = service.projectFromScoredEvent(scoredEvent(
                RiskLevel.CRITICAL,
                List.of()
        )).getFirst();

        assertThat(diagnostic.getReasonCode()).isNull();
        assertThat(diagnostic.getAttributes())
                .containsEntry("reasonCodeApplicable", false)
                .containsEntry("reasonCodeState", "empty")
                .containsEntry("supportedEvidenceCreated", false);
    }

    @Test
    void unsupportedDiagnosticDoesNotStoreRawReasonCode() {
        EvidenceDocument diagnostic = service.projectFromScoredEvent(scoredEvent(
                RiskLevel.HIGH,
                List.of("legacy-risk-marker")
        )).getFirst();

        assertThat(diagnostic.getValue()).isEqualTo("unsupported_reason_code");
        assertThat(diagnostic.getAttributes()).doesNotContainValue("legacy-risk-marker");
    }

    @Test
    void unsupportedDiagnosticStoresSafeLengthIfRawPresent() {
        EvidenceDocument diagnostic = service.projectFromScoredEvent(scoredEvent(
                RiskLevel.HIGH,
                List.of("legacy-risk-marker")
        )).getFirst();

        assertThat(diagnostic.getAttributes())
                .containsEntry("unsupportedReasonCodeLength", "legacy-risk-marker".length());
    }

    @Test
    void unsupportedDiagnosticKeepsSupportedEvidenceCreatedFalse() {
        EvidenceDocument diagnostic = service.projectFromScoredEvent(scoredEvent(
                RiskLevel.HIGH,
                List.of("legacy-risk-marker")
        )).getFirst();

        assertThat(diagnostic.getAttributes()).containsEntry("supportedEvidenceCreated", false);
    }
}
