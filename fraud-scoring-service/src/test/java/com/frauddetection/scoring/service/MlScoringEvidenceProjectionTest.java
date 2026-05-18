package com.frauddetection.scoring.service;

import com.frauddetection.common.events.evidence.ScoringEvidenceStatus;
import com.frauddetection.common.events.evidence.ScoringEvidenceType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.reason.ReasonCode;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.domain.MlModelOutput;
import com.frauddetection.scoring.observability.ScoringMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MlScoringEvidenceProjectionTest {

    @Test
    void supportedMlReasonCodesCreateAvailableEvidenceAndUnsupportedCodesCreateDiagnostic() {
        MlFraudScoringEngine engine = new MlFraudScoringEngine(input -> new MlModelOutput(
                true,
                0.91d,
                RiskLevel.CRITICAL,
                "python-logistic-fraud-model",
                "test-version",
                Instant.now(),
                Arrays.asList("MODEL_HIGH_RISK", "countryMismatch", "FRAUD_CONFIRMED", "AML_ESCALATION_REQUIRED"),
                Map.of("modelAvailable", true),
                Map.of("modelAvailable", true),
                null
        ), new ScoringMetrics(new SimpleMeterRegistry()));

        var result = engine.score(FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build()));

        assertThat(result.reasonCodes()).containsExactly(
                ReasonCode.MODEL_HIGH_RISK.wireValue(),
                ReasonCode.COUNTRY_MISMATCH.wireValue()
        );
        assertThat(result.scoringEvidence())
                .anySatisfy(item -> {
                    assertThat(item.reasonCode()).isEqualTo(ReasonCode.MODEL_HIGH_RISK.wireValue());
                    assertThat(item.status()).isEqualTo(ScoringEvidenceStatus.AVAILABLE);
                })
                .anySatisfy(item -> {
                    assertThat(item.evidenceType()).isEqualTo(ScoringEvidenceType.DIAGNOSTIC);
                    assertThat(item.status()).isEqualTo(ScoringEvidenceStatus.PARTIAL);
                    assertThat(item.reasonCode()).isNull();
                    assertThat(item.attributes()).containsEntry("unsupportedReasonCodeCount", 2);
                    assertThat(item.attributes().toString()).doesNotContain("FRAUD_CONFIRMED", "AML_ESCALATION_REQUIRED");
                });
        assertThat(result.scoringEvidence()).noneMatch(item -> ReasonCode.UNKNOWN.wireValue().equals(item.reasonCode()));
    }
}
