package com.frauddetection.scoring.evidence;

import com.frauddetection.common.events.evidence.ScoringEvidenceType;
import com.frauddetection.common.events.reason.ReasonCode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringEvidenceReasonCodeCompatibilityTest {

    private final ReasonCodeScoringEvidenceTypeMapper mapper = new ReasonCodeScoringEvidenceTypeMapper();

    @Test
    void everySupportedReasonCodeExceptUnknownMapsToEvidenceType() {
        assertThat(Arrays.stream(ReasonCode.values()).filter(reasonCode -> reasonCode != ReasonCode.UNKNOWN))
                .allSatisfy(reasonCode -> assertThat(mapper.map(reasonCode))
                        .as(reasonCode.name())
                        .isPresent());
    }

    @Test
    void unknownMapsToEmpty() {
        assertThat(mapper.map(ReasonCode.UNKNOWN)).isEmpty();
    }

    @Test
    void mappingsDoNotCreateVerdictOrProofSemantics() {
        assertThat(Arrays.stream(ReasonCode.values())
                .flatMap(reasonCode -> mapper.map(reasonCode).stream())
                .map(Enum::name)
                .map(name -> name.toLowerCase(Locale.ROOT)))
                .allSatisfy(name -> assertThat(name)
                        .doesNotContain("verdict")
                        .doesNotContain("proof")
                        .doesNotContain("confirmed")
                        .doesNotContain("final"));
    }

    @Test
    void rapidTransferFraudCaseRemainsVelocitySignalCandidateOnly() {
        assertThat(mapper.map(ReasonCode.RAPID_TRANSFER_FRAUD_CASE))
                .contains(ScoringEvidenceType.VELOCITY_SIGNAL);
    }
}
