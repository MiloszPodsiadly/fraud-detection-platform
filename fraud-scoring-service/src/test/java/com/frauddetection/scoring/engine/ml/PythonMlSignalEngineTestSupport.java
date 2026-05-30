package com.frauddetection.scoring.engine.ml;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.observability.ScoringMetrics;
import com.frauddetection.scoring.service.MlFraudScoringEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

final class PythonMlSignalEngineTestSupport {

    static final Instant RECEIVED_AT = Instant.parse("2026-05-30T10:00:00Z");

    private PythonMlSignalEngineTestSupport() {
    }

    static RecordingMlSource sourceReturning(FraudScoreResult result) {
        return new RecordingMlSource(request -> result);
    }

    static RecordingMlSource sourceThrowing(RuntimeException exception) {
        return new RecordingMlSource(request -> {
            throw exception;
        });
    }

    static FraudScoreResult validResult(double score, RiskLevel riskLevel) {
        return result(score, riskLevel, "python-logistic-fraud-model", "2026-05-30.v1", true, List.of());
    }

    static FraudScoreResult unavailableResult() {
        return result(0.0d, RiskLevel.LOW, "python-logistic-fraud-model", "unavailable", false,
                List.of(PythonMlSignalReasonCode.ML_MODEL_UNAVAILABLE.wireValue()));
    }

    static FraudScoreResult result(
            Double score,
            RiskLevel riskLevel,
            String modelName,
            String modelVersion,
            Boolean modelAvailable,
            List<String> reasonCodes
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (modelAvailable != null) {
            metadata.put("modelAvailable", modelAvailable);
        }
        return new FraudScoreResult(
                score,
                riskLevel,
                "ML",
                modelName,
                modelVersion,
                Instant.parse("2026-05-30T09:59:59Z"),
                reasonCodes,
                new LinkedHashMap<>(),
                Map.of(),
                metadata,
                Boolean.TRUE.equals(modelAvailable) && (riskLevel == RiskLevel.HIGH || riskLevel == RiskLevel.CRITICAL)
        );
    }

    static FraudScoreResult resultWithUnsafeDiagnostics() {
        Map<String, Object> scoreDetails = new LinkedHashMap<>();
        scoreDetails.put("rawFeatureVector", "VIP crypto EUR 50000 tx-secret acct-secret");
        scoreDetails.put("rawModelPayload", "{\"token\":\"secret\",\"host\":\"http://ml-internal\"}");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("modelAvailable", true);
        metadata.put("rawResponseJson", "{\"stacktrace\":\"PythonException\"}");
        return new FraudScoreResult(
                0.82d,
                RiskLevel.HIGH,
                "ML",
                "python-logistic-fraud-model",
                "2026-05-30.v1",
                Instant.parse("2026-05-30T09:59:59Z"),
                List.of("VIP", "rawResponseJson"),
                scoreDetails,
                Map.of("customerSegment", "VIP"),
                metadata,
                true
        );
    }

    static ScoringContext context() {
        TransactionEnrichedEvent event = TransactionFixtures.enrichedTransaction().build();
        return new ScoringContext(
                event,
                event.featureSnapshot(),
                ScoringMode.ML,
                event.correlationId(),
                RECEIVED_AT
        );
    }

    static String flatten(FraudEngineResult result) {
        return result.reasonCodes() + " "
                + result.contributions() + " "
                + result.evidence() + " "
                + result.statusReason() + " "
                + result.modelName() + " "
                + result.modelVersion();
    }

    static final class RecordingMlSource extends MlFraudScoringEngine {
        private final Function<FraudScoringRequest, FraudScoreResult> handler;
        private int calls;
        private FraudScoringRequest lastRequest;

        private RecordingMlSource(Function<FraudScoringRequest, FraudScoreResult> handler) {
            super(input -> unavailableModelOutput(), new ScoringMetrics(new SimpleMeterRegistry()));
            this.handler = handler;
        }

        @Override
        public FraudScoreResult score(FraudScoringRequest request) {
            calls++;
            lastRequest = request;
            return handler.apply(request);
        }

        int calls() {
            return calls;
        }

        FraudScoringRequest lastRequest() {
            return lastRequest;
        }
    }

    private static com.frauddetection.scoring.domain.MlModelOutput unavailableModelOutput() {
        return new com.frauddetection.scoring.domain.MlModelOutput(
                false,
                0.0d,
                RiskLevel.LOW,
                "test",
                "unavailable",
                RECEIVED_AT,
                List.of(PythonMlSignalReasonCode.ML_MODEL_UNAVAILABLE.wireValue()),
                Map.of(),
                Map.of("modelAvailable", false),
                "unavailable"
        );
    }
}
