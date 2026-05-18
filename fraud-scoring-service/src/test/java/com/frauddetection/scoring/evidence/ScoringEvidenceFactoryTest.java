package com.frauddetection.scoring.evidence;

import com.frauddetection.common.events.evidence.ScoringEvidenceSource;
import com.frauddetection.common.events.evidence.ScoringEvidenceStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.reason.ReasonCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringEvidenceFactoryTest {

    private final ScoringEvidenceFactory factory = new ScoringEvidenceFactory();

    @Test
    void evidenceIdIsEventLocalAndNullSafeForDiagnosticCode() {
        var evidence = factory.mlRuntimeDiagnostic(null, Instant.now(), 7);

        assertThat(evidence.evidenceId()).isEqualTo("ML_RUNTIME:missing-code:7");
        assertThat(evidence.attributes()).containsEntry("scoringEvidenceState", "missing-code");
    }

    @Test
    void unsupportedDiagnosticWithNoUnsupportedInputsDoesNotCrashAndUsesNone() {
        var evidence = factory.unsupportedReasonCodeDiagnostic(
                ReasonCode.parseLegacyList(List.of(ReasonCode.COUNTRY_MISMATCH.wireValue())),
                ScoringEvidenceSource.ML_MODEL,
                Instant.now(),
                0
        );

        assertThat(evidence.attributes())
                .containsEntry("unsupportedReasonCodeCount", 0)
                .containsEntry("parseStatus", "NONE");
    }

    @Test
    void missingSupportedReasonCodesDiagnosticHasReasonCodeApplicableFalse() {
        var evidence = factory.missingSupportedReasonCodes(
                ScoringEvidenceSource.ML_MODEL,
                RiskLevel.CRITICAL,
                Instant.now(),
                0
        );

        assertThat(evidence.status()).isEqualTo(ScoringEvidenceStatus.PARTIAL);
        assertThat(evidence.reasonCode()).isNull();
        assertThat(evidence.attributes())
                .containsEntry("diagnostic", true)
                .containsEntry("supportedEvidenceCreated", false)
                .containsEntry("reasonCodeApplicable", false);
    }
}
