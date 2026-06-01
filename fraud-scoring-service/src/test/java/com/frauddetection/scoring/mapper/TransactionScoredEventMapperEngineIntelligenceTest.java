package com.frauddetection.scoring.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionScoredEventMapperEngineIntelligenceTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-05-31T10:00:00Z");

    private final TransactionScoredEventMapper mapper = new TransactionScoredEventMapper();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void mapperOmitsEngineIntelligenceWhenOptionalEmpty() throws Exception {
        var event = mapper.toEvent(request(), scoreResult(), Optional.empty());

        assertThat(event.engineIntelligence()).isNull();
        assertThat(objectMapper.writeValueAsString(event)).doesNotContain("\"engineIntelligence\"");
    }

    @Test
    void mapperIncludesEngineIntelligenceWhenProvided() throws Exception {
        var event = mapper.toEvent(request(), scoreResult(), Optional.of(summary()));

        assertThat(event.engineIntelligence()).isEqualTo(summary());
        assertThat(objectMapper.writeValueAsString(event)).contains("\"engineIntelligence\"");
    }

    @Test
    void mapperDoesNotChangeExistingEventFieldsWhenEngineIntelligenceProvided() {
        var withoutSummary = mapper.toEvent(request(), scoreResult(), Optional.empty());
        var withSummary = mapper.toEvent(request(), scoreResult(), Optional.of(summary()));

        assertThat(withSummary)
                .usingRecursiveComparison()
                .ignoringFields("eventId", "createdAt", "engineIntelligence")
                .isEqualTo(withoutSummary);
    }

    private FraudScoringRequest request() {
        return FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build());
    }

    private FraudScoreResult scoreResult() {
        return new FraudScoreResult(
                0.91d,
                RiskLevel.CRITICAL,
                "RULE_BASED",
                "rule-based-engine",
                "v1",
                GENERATED_AT,
                List.of("HIGH_VELOCITY"),
                Map.of("finalScore", 0.91d),
                Map.of("transactionAmount", 100.0d),
                Map.of(),
                true
        );
    }

    private EngineIntelligenceSummary summary() {
        return new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                GENERATED_AT,
                List.of(),
                new EngineIntelligenceComparison(
                        EngineIntelligenceAgreementStatus.INSUFFICIENT_DATA,
                        EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                        EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
                ),
                List.of(),
                List.of()
        );
    }
}
