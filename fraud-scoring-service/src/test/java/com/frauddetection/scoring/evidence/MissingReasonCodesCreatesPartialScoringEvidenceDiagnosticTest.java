package com.frauddetection.scoring.evidence;

import com.frauddetection.common.events.evidence.ScoringEvidenceStatus;
import com.frauddetection.common.events.evidence.ScoringEvidenceType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.reason.ReasonCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MissingReasonCodesCreatesPartialScoringEvidenceDiagnosticTest {

    @Test
    void highRiskWithoutSupportedReasonCodesCreatesDiagnosticEvidence() {
        ScoringEvidenceFactory factory = new ScoringEvidenceFactory();

        var evidence = factory.modelEvidence(
                ReasonCode.parseLegacyList(List.of()),
                true,
                RiskLevel.CRITICAL,
                Instant.now(),
                null
        );

        assertThat(evidence).singleElement().satisfies(item -> {
            assertThat(item.evidenceType()).isEqualTo(ScoringEvidenceType.DIAGNOSTIC);
            assertThat(item.status()).isEqualTo(ScoringEvidenceStatus.PARTIAL);
            assertThat(item.reasonCode()).isNull();
            assertThat(item.attributes()).containsEntry("scoringEvidenceState", "missing_supported_reason_codes");
        });
    }

    @Test
    void lowRiskWithoutReasonCodesDoesNotCreateFakeAvailableEvidence() {
        ScoringEvidenceFactory factory = new ScoringEvidenceFactory();

        assertThat(factory.modelEvidence(ReasonCode.parseLegacyList(List.of()), true, RiskLevel.LOW, Instant.now(), null))
                .isEmpty();
    }
}
