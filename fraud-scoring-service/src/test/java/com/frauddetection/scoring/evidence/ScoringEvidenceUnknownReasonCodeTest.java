package com.frauddetection.scoring.evidence;

import com.frauddetection.common.events.evidence.ScoringEvidenceSource;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.reason.ReasonCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringEvidenceUnknownReasonCodeTest {

    private final ScoringEvidenceFactory factory = new ScoringEvidenceFactory();

    @Test
    void unknownCannotBecomeSupportedEvidence() {
        assertThat(factory.supported(
                ReasonCode.UNKNOWN,
                ScoringEvidenceSource.ML_MODEL,
                RiskLevel.HIGH,
                Instant.now(),
                0,
                Map.of()
        )).isEmpty();

        assertThat(factory.supportedReasonCodes(
                List.of(ReasonCode.UNKNOWN.wireValue()),
                ScoringEvidenceSource.ML_MODEL,
                RiskLevel.HIGH,
                Instant.now()
        )).isEmpty();
    }
}
