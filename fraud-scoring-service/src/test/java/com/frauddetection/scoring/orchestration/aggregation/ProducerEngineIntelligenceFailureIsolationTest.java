package com.frauddetection.scoring.orchestration.aggregation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.mapper.TransactionScoredEventMapper;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionTestSupport.pipeline;
import static com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionTestSupport.request;
import static com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionTestSupport.service;

class ProducerEngineIntelligenceFailureIsolationTest {

    @Test
    void aggregationFailureReturnsBaseEventWithoutNestedField() throws Exception {
        FraudScoringOrchestrator orchestrator = mock(FraudScoringOrchestrator.class);
        when(orchestrator.evaluate(any())).thenThrow(new IllegalStateException("raw-secret-must-not-leak"));
        var intelligence = service(true, pipeline(
                orchestrator, mock(FraudEngineAggregationService.class), new PublicEngineIntelligenceMapper()
        ))
                .emitIfEnabled(request());
        var event = new TransactionScoredEventMapper().toEvent(request(), scoreResult(), intelligence);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(event);

        assertThat(intelligence).isEmpty();
        assertThat(json).doesNotContain("engineIntelligence", "raw-secret-must-not-leak");
    }

    @Test
    void mapperFailureReturnsEmptyOptional() {
        PublicEngineIntelligenceMapper mapper = mock(PublicEngineIntelligenceMapper.class);
        FraudEngineAggregationResult aggregation = mock(FraudEngineAggregationResult.class);
        FraudScoringOrchestrator orchestrator = mock(FraudScoringOrchestrator.class);
        FraudEngineAggregationService aggregationService = mock(FraudEngineAggregationService.class);
        when(orchestrator.evaluate(any())).thenReturn(mock(FraudScoringOrchestrationResult.class));
        when(aggregationService.aggregate(any())).thenReturn(aggregation);
        when(mapper.map(aggregation)).thenThrow(new IllegalStateException("raw-secret-must-not-leak"));
        var emission = service(true, pipeline(orchestrator, aggregationService, mapper));

        assertThat(emission.emitIfEnabled(request())).isEmpty();
    }

    @Test
    void warningIsBoundedAndDoesNotLogRawException() throws Exception {
        Path source = moduleRoot().resolve(
                "src/main/java/com/frauddetection/scoring/orchestration/aggregation/EngineIntelligenceEmissionService.java"
        );

        assertThat(Files.readString(source))
                .contains("log.warn(\"Engine intelligence enrichment omitted.\")")
                .doesNotContain("exception.getMessage()", "log.warn(\"Engine intelligence enrichment omitted.\", exception)");
    }

    private FraudScoreResult scoreResult() {
        return new FraudScoreResult(
                0.91d,
                RiskLevel.CRITICAL,
                "RULE_BASED",
                "rule-based-engine",
                "v1",
                Instant.parse("2026-05-31T10:00:00Z"),
                List.of("HIGH_VELOCITY"),
                Map.of(),
                Map.of(),
                Map.of(),
                true
        );
    }

    private Path moduleRoot() {
        Path current = Path.of(".").toAbsolutePath().normalize();
        return Files.exists(current.resolve("src/main"))
                ? current
                : current.resolve("fraud-scoring-service");
    }
}
