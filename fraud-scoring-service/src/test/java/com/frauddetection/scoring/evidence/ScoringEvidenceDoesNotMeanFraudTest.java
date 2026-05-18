package com.frauddetection.scoring.evidence;

import com.frauddetection.common.events.evidence.ScoringEvidenceSource;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.reason.ReasonCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringEvidenceDoesNotMeanFraudTest {

    @Test
    void supportedEvidenceTextDoesNotClaimConfirmedFraudVerdictOrProof() {
        ScoringEvidenceFactory factory = new ScoringEvidenceFactory();

        var evidence = factory.supported(
                ReasonCode.RAPID_TRANSFER_FRAUD_CASE,
                ScoringEvidenceSource.RULE_BASED_SCORING,
                RiskLevel.HIGH,
                Instant.now(),
                0,
                Map.of()
        ).orElseThrow();

        String text = (evidence.title() + " " + evidence.description()).toLowerCase(Locale.ROOT);
        assertThat(text)
                .doesNotContain("confirmed")
                .doesNotContain("proof")
                .doesNotContain("verdict")
                .doesNotContain("final outcome")
                .doesNotContain("case exists");
    }
}
