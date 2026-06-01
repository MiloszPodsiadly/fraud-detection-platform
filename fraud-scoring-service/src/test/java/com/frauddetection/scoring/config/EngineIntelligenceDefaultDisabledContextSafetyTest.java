package com.frauddetection.scoring.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.mapper.TransactionScoredEventMapper;
import com.frauddetection.scoring.messaging.TransactionScoredEventPublisher;
import com.frauddetection.scoring.observability.ScoringMetrics;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceDiagnosticEnrichmentPipeline;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionService;
import com.frauddetection.scoring.orchestration.aggregation.FraudEngineAggregationService;
import com.frauddetection.scoring.orchestration.aggregation.PublicEngineIntelligenceMapper;
import com.frauddetection.scoring.service.FraudScoringEngine;
import com.frauddetection.scoring.service.MlFraudScoringEngine;
import com.frauddetection.scoring.service.TransactionFraudScoringService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.frauddetection.scoring.config.EngineIntelligenceSpringContextTestSupport.contextRunner;
import static com.frauddetection.scoring.config.EngineIntelligenceSpringContextTestSupport.enabledContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EngineIntelligenceDefaultDisabledContextSafetyTest {

    @Test
    void defaultDisabledApplicationContextStartsWithoutDiagnosticDependencies() {
        contextRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EngineIntelligenceEmissionService.class);
            assertThat(context).doesNotHaveBean(EngineIntelligenceDiagnosticEnrichmentPipeline.class);
            assertThat(context).doesNotHaveBean(FraudScoringOrchestrator.class);
        });
    }

    @Test
    void enabledContextStartsWithDiagnosticPipeline() {
        enabledContextRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EngineIntelligenceEmissionService.class);
            assertThat(context).hasSingleBean(EngineIntelligenceDiagnosticEnrichmentPipeline.class);
            assertThat(context).hasSingleBean(FraudScoringOrchestrator.class);
        });
    }

    @Test
    void defaultDisabledScoringPublishesOldShapeWithoutInvokingDiagnosticGraph() {
        contextRunner().run(context -> {
            FraudScoringOrchestrator orchestrator = mock(FraudScoringOrchestrator.class);
            FraudEngineAggregationService aggregation = mock(FraudEngineAggregationService.class);
            PublicEngineIntelligenceMapper mapper = mock(PublicEngineIntelligenceMapper.class);
            TransactionScoredEventPublisher publisher = mock(TransactionScoredEventPublisher.class);
            FraudScoringEngine baseline = mock(FraudScoringEngine.class);
            var input = TransactionFixtures.enrichedTransaction().build();
            when(baseline.score(com.frauddetection.scoring.domain.FraudScoringRequest.from(input)))
                    .thenReturn(scoreResult());
            var service = new TransactionFraudScoringService(
                    baseline,
                    new TransactionScoredEventMapper(),
                    publisher,
                    context.getBean(ScoringProperties.class),
                    new ScoringMetrics(new SimpleMeterRegistry()),
                    context.getBean(EngineIntelligenceEmissionService.class)
            );

            service.score(input);

            ArgumentCaptor<TransactionScoredEvent> captor = ArgumentCaptor.forClass(TransactionScoredEvent.class);
            verify(publisher).publish(captor.capture());
            assertThat(captor.getValue().engineIntelligence()).isNull();
            assertThat(json(captor.getValue()))
                    .doesNotContain("\"engineIntelligence\"");
            verify(orchestrator, never()).evaluate(org.mockito.ArgumentMatchers.any());
            verify(aggregation, never()).aggregate(org.mockito.ArgumentMatchers.any());
            verify(mapper, never()).map(org.mockito.ArgumentMatchers.any());
        });
    }

    @Test
    void defaultDisabledContextDoesNotRequireExternalMlAvailability() {
        MlFraudScoringEngine ml = mock(MlFraudScoringEngine.class);
        doThrow(new IllegalStateException("external-ml-must-not-be-called")).when(ml)
                .score(org.mockito.ArgumentMatchers.any());

        contextRunner()
                .withBean(MlFraudScoringEngine.class, () -> ml)
                .run(context -> {
                    TransactionScoredEventPublisher publisher = mock(TransactionScoredEventPublisher.class);
                    FraudScoringEngine baseline = mock(FraudScoringEngine.class);
                    var input = TransactionFixtures.enrichedTransaction().build();
                    when(baseline.score(com.frauddetection.scoring.domain.FraudScoringRequest.from(input)))
                            .thenReturn(scoreResult());
                    var service = new TransactionFraudScoringService(
                            baseline,
                            new TransactionScoredEventMapper(),
                            publisher,
                            context.getBean(ScoringProperties.class),
                            new ScoringMetrics(new SimpleMeterRegistry()),
                            context.getBean(EngineIntelligenceEmissionService.class)
                    );

                    service.score(input);

                    verify(publisher).publish(org.mockito.ArgumentMatchers.any());
                    verifyNoInteractions(ml);
                });
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
                Map.of("finalScore", 0.91d),
                Map.of(),
                Map.of(),
                true
        );
    }

    private String json(TransactionScoredEvent event) {
        try {
            return new ObjectMapper().findAndRegisterModules().writeValueAsString(event);
        } catch (Exception exception) {
            throw new IllegalStateException("TEST_JSON_SERIALIZATION_FAILED", exception);
        }
    }
}
