package com.frauddetection.scoring.service;

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

class MlFraudScoringEngineTest {

    @Test
    void shouldNormalizeLegacyModelReasonCodesAndSurfaceUnsupportedCount() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        MlFraudScoringEngine engine = new MlFraudScoringEngine(input -> new MlModelOutput(
                true,
                0.82d,
                RiskLevel.HIGH,
                "python-logistic-fraud-model",
                "test-version",
                Instant.now(),
                Arrays.asList("countryMismatch", "some-new-future-code", " ", null),
                Map.of("modelAvailable", true),
                Map.of("modelAvailable", true),
                null
        ), new ScoringMetrics(meterRegistry));

        var result = engine.score(FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build()));

        assertThat(result.reasonCodes()).containsExactly(
                ReasonCode.COUNTRY_MISMATCH.wireValue(),
                ReasonCode.UNKNOWN.wireValue()
        );
        assertThat(result.scoreDetails()).containsEntry("unsupportedReasonCodeCount", 3);
        assertThat(result.explanationMetadata()).containsEntry("unsupportedReasonCodeCount", 3);
        assertThat(meterRegistry.get("fraud.scoring.reason_code.parse.unsupported")
                .tags("service", "fraud-scoring-service", "source", "ml_model", "parser_mode", "legacy")
                .counter()
                .count()).isEqualTo(3.0d);
    }

    @Test
    void shouldTreatNullModelReasonCodeListAsNoReasonCodeData() {
        MlFraudScoringEngine engine = new MlFraudScoringEngine(input -> new MlModelOutput(
                true,
                0.12d,
                RiskLevel.LOW,
                "python-logistic-fraud-model",
                "test-version",
                Instant.now(),
                null,
                Map.of("modelAvailable", true),
                Map.of("modelAvailable", true),
                null
        ), new ScoringMetrics(new SimpleMeterRegistry()));

        var result = engine.score(FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build()));

        assertThat(result.reasonCodes()).isEmpty();
        assertThat(result.scoreDetails()).doesNotContainKey("unsupportedReasonCodeCount");
    }
}
