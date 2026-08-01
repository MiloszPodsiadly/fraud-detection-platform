package com.frauddetection.alert.engineintelligence;

import com.frauddetection.alert.api.EngineIntelligenceResponse;
import com.frauddetection.alert.api.EngineIntelligenceEngineStatusResponse;
import com.frauddetection.alert.api.EngineIntelligenceResponseStatus;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadModelMapper;
import com.frauddetection.alert.mapper.EngineIntelligenceResponseMapper;
import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.enricher.domain.EnrichedTransactionFeatures;
import com.frauddetection.enricher.domain.FeatureStoreSnapshot;
import com.frauddetection.enricher.domain.RecentTransaction;
import com.frauddetection.enricher.service.CurrencyAmountConverter;
import com.frauddetection.enricher.service.TransactionFeatureCalculator;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.domain.MlModelOutput;
import com.frauddetection.scoring.engine.ml.PythonMlSignalEngine;
import com.frauddetection.scoring.engine.rules.RuleBasedSignalEngine;
import com.frauddetection.scoring.engine.velocity.VelocitySignalEngine;
import com.frauddetection.scoring.features.FeatureSnapshotReaderFactory;
import com.frauddetection.scoring.mapper.TransactionScoredEventMapper;
import com.frauddetection.scoring.observability.ScoringMetrics;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationResult;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import com.frauddetection.scoring.orchestration.FraudSignalEngineRegistry;
import com.frauddetection.scoring.orchestration.aggregation.FraudEngineAggregationPolicy;
import com.frauddetection.scoring.orchestration.aggregation.FraudEngineAggregationService;
import com.frauddetection.scoring.orchestration.aggregation.PublicEngineIntelligenceMapper;
import com.frauddetection.scoring.service.MlFraudScoringEngine;
import com.frauddetection.scoring.service.RuleBasedFraudScoringEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceFullPathCompositionTest {

    @Test
    void featureEnricherOutputSurvivesRulesMlVelocityEventProjectionAndApiWithoutDecisionMutation() {
        TransactionEnrichedEvent enriched = enrichedEventFromRealFeatureCalculator();
        FraudScoringRequest request = FraudScoringRequest.from(enriched);
        RuleBasedFraudScoringEngine baselineEngine = ruleBasedFraudScoringEngine();
        FraudScoreResult baselineResult = baselineEngine.score(request);
        ScoringContext context = new ScoringContext(
                enriched,
                enriched.featureSnapshot(),
                ScoringMode.ML,
                enriched.correlationId(),
                Instant.parse("2026-06-18T10:00:00Z")
        );

        FraudScoringOrchestrationResult orchestrationResult = new FraudScoringOrchestrator(
                new FraudSignalEngineRegistry(List.of(
                        new RuleBasedSignalEngine(new FeatureSnapshotReaderFactory(), baselineEngine),
                        new PythonMlSignalEngine(new MlFraudScoringEngine(
                                input -> new MlModelOutput(
                                        true,
                                        0.62d,
                                        RiskLevel.MEDIUM,
                                        "python-logistic-fraud-model",
                                        "2026-06-18.v1",
                                        Instant.parse("2026-06-18T09:59:59Z"),
                                        List.of("MODEL_MEDIUM_RISK"),
                                        Map.of("modelScoreBucket", "MEDIUM"),
                                        Map.of("modelAvailable", true),
                                        null
                                ),
                                new ScoringMetrics(new SimpleMeterRegistry())
                        )),
                        new VelocitySignalEngine(new FeatureSnapshotReaderFactory())
                ))
        ).evaluate(context);

        var summary = new PublicEngineIntelligenceMapper().map(
                new FraudEngineAggregationService(FraudEngineAggregationPolicy.defaultInternalPolicy())
                        .aggregate(orchestrationResult)
        );
        TransactionScoredEvent event = new TransactionScoredEventMapper().toEvent(
                request,
                baselineResult,
                Optional.of(summary)
        );
        EngineIntelligenceProjection projection = new EngineIntelligenceProjectionMapper(
                new EngineIntelligenceProjectionPolicy()
        ).map(event.transactionId(), event.engineIntelligence(), null).projection().orElseThrow();
        EngineIntelligenceResponse response = new EngineIntelligenceResponseMapper().toResponse(
                new EngineIntelligenceReadModelMapper().map(projection)
        );

        assertThat(event.fraudScore()).isEqualTo(baselineResult.fraudScore());
        assertThat(event.riskLevel()).isEqualTo(baselineResult.riskLevel());
        assertThat(event.alertRecommended()).isEqualTo(baselineResult.alertRecommended());
        assertThat(event.reasonCodes()).isEqualTo(baselineResult.reasonCodes());
        assertThat(event.toString()).doesNotContain("finalDecision", "recommendedAction", "velocityScore");

        assertThat(response.status()).isEqualTo(EngineIntelligenceResponseStatus.AVAILABLE);
        assertThat(response.warnings()).isEmpty();
        assertThat(response.engines()).extracting(engine -> engine.engineId())
                .containsExactlyElementsOf(FraudEngineIdentityContract.engineOrder());
        assertThat(response.engines()).extracting(engine -> engine.status())
                .containsExactly(
                        EngineIntelligenceEngineStatusResponse.AVAILABLE,
                        EngineIntelligenceEngineStatusResponse.AVAILABLE,
                        EngineIntelligenceEngineStatusResponse.AVAILABLE
                );
        assertThat(response.diagnosticSignals()).extracting(signal -> signal.engineId())
                .contains(FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID);
        EngineIntelligenceComparison twoEngineComparison = twoEngineComparison(context);
        assertThat(response.comparison().agreementStatus()).isEqualTo(twoEngineComparison.agreementStatus());
        assertThat(response.comparison().riskMismatchStatus()).isEqualTo(twoEngineComparison.riskMismatchStatus());
        assertThat(response.comparison().scoreDeltaBucket()).isEqualTo(twoEngineComparison.scoreDeltaBucket());
        assertThat(response.toString()).doesNotContain(
                enriched.transactionId(),
                "velocityScore",
                "rapidTransferTransactionIds",
                "finalDecision",
                "recommendedAction"
        );
    }

    private TransactionEnrichedEvent enrichedEventFromRealFeatureCalculator() {
        TransactionRawEvent raw = TransactionFixtures.rawTransaction()
                .withTransactionId("txn-full-path-velocity")
                .withAmount(new BigDecimal("100.00"), "PLN")
                .build();
        EnrichedTransactionFeatures features = new TransactionFeatureCalculator(new CurrencyAmountConverter())
                .calculate(raw, velocityReadySnapshot());

        return new TransactionEnrichedEvent(
                raw.eventId(),
                raw.transactionId(),
                raw.correlationId(),
                raw.customerId(),
                raw.accountId(),
                raw.createdAt(),
                raw.transactionTimestamp(),
                raw.transactionAmount(),
                raw.merchantInfo(),
                raw.deviceInfo(),
                raw.locationInfo(),
                raw.customerContext(),
                features.recentTransactionCount(),
                features.recentTransactionCountWindow(),
                features.recentAmountSum(),
                features.recentAmountSumWindow(),
                features.transactionVelocityPerMinute(),
                features.merchantFrequency7d(),
                features.deviceNovelty(),
                features.countryMismatch(),
                features.proxyOrVpnDetected(),
                features.featureFlags(),
                features.featureSnapshot()
        );
    }

    private FeatureStoreSnapshot velocityReadySnapshot() {
        return new FeatureStoreSnapshot(
                4,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(
                        recentTransaction("hist-1"),
                        recentTransaction("hist-2"),
                        recentTransaction("hist-3"),
                        recentTransaction("hist-4")
                ),
                0,
                Instant.parse("2026-06-18T09:59:30Z"),
                true
        );
    }

    private RecentTransaction recentTransaction(String transactionId) {
        return new RecentTransaction(
                transactionId,
                Instant.parse("2026-06-18T09:59:30Z"),
                BigDecimal.ZERO,
                "PLN",
                BigDecimal.ZERO
        );
    }

    private RuleBasedFraudScoringEngine ruleBasedFraudScoringEngine() {
        return new RuleBasedFraudScoringEngine(new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED));
    }

    private EngineIntelligenceComparison twoEngineComparison(ScoringContext context) {
        FraudScoringOrchestrationResult twoEngine = new FraudScoringOrchestrator(
                new FraudSignalEngineRegistry(List.of(
                        new RuleBasedSignalEngine(new FeatureSnapshotReaderFactory(), ruleBasedFraudScoringEngine()),
                        new PythonMlSignalEngine(new MlFraudScoringEngine(
                                input -> new MlModelOutput(
                                        true,
                                        0.62d,
                                        RiskLevel.MEDIUM,
                                        "python-logistic-fraud-model",
                                        "2026-06-18.v1",
                                        Instant.parse("2026-06-18T09:59:59Z"),
                                        List.of("MODEL_MEDIUM_RISK"),
                                        Map.of("modelScoreBucket", "MEDIUM"),
                                        Map.of("modelAvailable", true),
                                        null
                                ),
                                new ScoringMetrics(new SimpleMeterRegistry())
                        ))
                ))
        ).evaluate(context);

        return new PublicEngineIntelligenceMapper().map(
                new FraudEngineAggregationService(FraudEngineAggregationPolicy.defaultInternalPolicy())
                        .aggregate(twoEngine)
        ).comparison();
    }
}
