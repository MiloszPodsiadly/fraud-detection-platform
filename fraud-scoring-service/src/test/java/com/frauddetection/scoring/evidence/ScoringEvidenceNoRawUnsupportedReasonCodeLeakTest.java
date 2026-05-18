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

    @Test
    void modelUnavailableDoesNotStoreRawFallbackReason() {
        ScoringEvidenceFactory factory = new ScoringEvidenceFactory();

        var evidence = factory.modelUnavailable(
                "ML inference service request failed at http://internal-host/token?customerId=123",
                Instant.now()
        );

        assertThat(evidence.attributes())
                .containsEntry("fallbackReasonCode", "ml_request_failed")
                .containsEntry("fallbackReasonProvided", true);
        assertThat(evidence.attributes()).containsKey("fallbackReasonLength");
        assertThat(evidence.attributes().toString())
                .doesNotContain("http://internal-host")
                .doesNotContain("customerId")
                .doesNotContain("request failed at");
    }

    @Test
    void longFallbackReasonStoresLengthOnlyAndControlledCode() {
        ScoringEvidenceFactory factory = new ScoringEvidenceFactory();
        String fallbackReason = "x".repeat(300);

        var evidence = factory.modelUnavailable(fallbackReason, Instant.now());

        assertThat(evidence.attributes())
                .containsEntry("fallbackReasonCode", "unknown_fallback_reason")
                .containsEntry("fallbackReasonLength", 300)
                .containsEntry("fallbackReasonProvided", true);
        assertThat(evidence.attributes().toString()).doesNotContain(fallbackReason);
    }
}
