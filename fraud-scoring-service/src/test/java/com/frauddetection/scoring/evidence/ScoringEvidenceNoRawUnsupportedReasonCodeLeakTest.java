package com.frauddetection.scoring.evidence;

import com.frauddetection.common.events.evidence.ScoringEvidenceStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.reason.ReasonCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringEvidenceNoRawUnsupportedReasonCodeLeakTest {

    @Test
    void unsupportedModelReasonCodesCreateDiagnosticWithoutStoringRawValues() {
        ScoringEvidenceFactory factory = new ScoringEvidenceFactory();
        var evidence = factory.modelEvidence(
                ReasonCode.parseLegacyList(Arrays.asList("FRAUD_CONFIRMED", "AML_ESCALATION_REQUIRED", "future-code")),
                true,
                RiskLevel.HIGH,
                Instant.now(),
                null
        );

        assertThat(evidence)
                .anySatisfy(item -> {
                    assertThat(item.status()).isEqualTo(ScoringEvidenceStatus.PARTIAL);
                    assertThat(item.reasonCode()).isNull();
                    assertThat(item.attributes()).containsEntry("unsupportedReasonCodeCount", 3);
                    assertThat(item.attributes().toString())
                            .doesNotContain("FRAUD_CONFIRMED")
                            .doesNotContain("AML_ESCALATION_REQUIRED")
                            .doesNotContain("future-code");
                });
    }
}
