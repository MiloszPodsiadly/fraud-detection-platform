package com.frauddetection.alert.engineintelligence;

import com.frauddetection.alert.api.EngineIntelligenceEngineStatusResponse;
import com.frauddetection.alert.api.EngineIntelligenceResponse;
import com.frauddetection.alert.api.EngineIntelligenceResponseStatus;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadModelMapper;
import com.frauddetection.alert.mapper.EngineIntelligenceResponseMapper;
import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.kafka.JacksonKafkaDeserializer;
import com.frauddetection.common.events.kafka.JacksonKafkaSerializer;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.enricher.domain.EnrichedTransactionFeatures;
import com.frauddetection.enricher.domain.FeatureStoreSnapshot;
import com.frauddetection.enricher.domain.RecentTransaction;
import com.frauddetection.enricher.mapper.TransactionEnrichedEventMapper;
import com.frauddetection.enricher.service.CurrencyAmountConverter;
import com.frauddetection.enricher.service.TransactionFeatureCalculator;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.domain.MlModelOutput;
import com.frauddetection.scoring.engine.FraudEngineDescriptor;
import com.frauddetection.scoring.engine.FraudSignalEngine;
import com.frauddetection.scoring.engine.FraudSignalEvaluation;
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
import com.frauddetection.scoring.orchestration.runtime.BoundedFraudEngineExecutor;
import com.frauddetection.scoring.orchestration.runtime.FraudScoringOrchestratorExecutionPolicy;
import com.frauddetection.scoring.orchestration.runtime.NoOpFraudScoringOrchestratorMetrics;
import com.frauddetection.scoring.service.MlFraudScoringEngine;
import com.frauddetection.scoring.service.RuleBasedFraudScoringEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceFullPathCompositionTest {
    private static final Instant RECEIVED_AT = Instant.parse("2026-06-18T10:00:00Z");
    private static final Instant GENERATED_AT = Instant.parse("2026-06-18T10:00:02Z");
    private static final String ENRICHED_TOPIC = "transactions.enriched";
    private static final String SCORED_TOPIC = "transactions.scored";
    private static final String ORCHESTRATOR_ENGINE_EXCEPTION = "ORCHESTRATOR_ENGINE_EXCEPTION";

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final JacksonKafkaSerializer<TransactionEnrichedEvent> enrichedSerializer = new JacksonKafkaSerializer<>();
    private final JacksonKafkaDeserializer<TransactionEnrichedEvent> enrichedDeserializer =
            new JacksonKafkaDeserializer<>(TransactionEnrichedEvent.class);
    private final JacksonKafkaSerializer<TransactionScoredEvent> scoredSerializer = new JacksonKafkaSerializer<>();
    private final JacksonKafkaDeserializer<TransactionScoredEvent> scoredDeserializer =
            new JacksonKafkaDeserializer<>(TransactionScoredEvent.class);

    @Test
    void featureEnricherKafkaScoringProjectionApiAndUiFixtureRemainOneCanonicalComposition() throws Exception {
        TransactionEnrichedEvent enriched = enrichedKafkaRoundTrip(enrichedEventFromRealFeatureCalculator());
        FraudScoringRequest request = FraudScoringRequest.from(enriched);
        RuleBasedFraudScoringEngine baselineEngine = ruleBasedFraudScoringEngine();
        FraudScoreResult baselineResult = baselineEngine.score(request);
        ScoringContext context = new ScoringContext(
                enriched,
                enriched.featureSnapshot(),
                ScoringMode.ML,
                enriched.correlationId(),
                RECEIVED_AT
        );

        TransactionScoredEvent event = scoredKafkaRoundTrip(new TransactionScoredEventMapper().toEvent(
                request,
                baselineResult,
                Optional.of(summary(evaluate(context, productionEngines(baselineEngine, true))))
        ));
        EngineIntelligenceResponse response = responseFor(event);

        BigDecimal enrichedRecentAmount = (BigDecimal) enriched.featureSnapshot()
                .get(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN);
        assertThat(enrichedRecentAmount)
                .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(enrichedRecentAmount)
                .isExactlyInstanceOf(BigDecimal.class);
        assertThat(enriched.featureSnapshot().get(FraudFeatureContract.RAPID_TRANSFER_THRESHOLD_PLN))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL)
                .isEqualByComparingTo(new BigDecimal("20000.00"));
        assertThat(enriched.featureSnapshot().get(FraudFeatureContract.RAPID_TRANSFER_THRESHOLD_PLN))
                .isExactlyInstanceOf(BigDecimal.class);
        BigDecimal scoredRecentAmount = (BigDecimal) event.featureSnapshot()
                .get(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN);
        assertThat(scoredRecentAmount)
                .isEqualByComparingTo(enrichedRecentAmount);
        assertThat(scoredRecentAmount)
                .isExactlyInstanceOf(BigDecimal.class);
        assertThat(event.featureSnapshot().get(FraudFeatureContract.RAPID_TRANSFER_THRESHOLD_PLN))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL)
                .isEqualByComparingTo(new BigDecimal("20000.00"));
        assertThat(event.featureSnapshot().get(FraudFeatureContract.RAPID_TRANSFER_THRESHOLD_PLN))
                .isExactlyInstanceOf(BigDecimal.class);

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
        assertThat(response.engines()).extracting(engine -> engine.reasonCodes())
                .containsExactly(
                        List.of("HIGH_VELOCITY"),
                        List.of("ML_MODEL_SIGNAL"),
                        List.of("TRANSACTION_VELOCITY")
                );
        assertThat(response.diagnosticSignals()).extracting(signal -> signal.engineId())
                .contains(FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID);
        EngineIntelligenceComparison twoEngineComparison = twoEngineComparison(context);
        assertThat(response.comparison().agreementStatus()).isEqualTo(twoEngineComparison.agreementStatus());
        assertThat(response.comparison().riskMismatchStatus()).isEqualTo(twoEngineComparison.riskMismatchStatus());
        assertThat(response.comparison().scoreDeltaBucket()).isEqualTo(twoEngineComparison.scoreDeltaBucket());
        assertThat(objectMapper.writeValueAsString(response))
                .doesNotContain(
                        enriched.transactionId(),
                        enriched.customerId(),
                        enriched.accountId(),
                        "device-1001",
                        "card-1001",
                        "hist-",
                        "velocityScore",
                        "rapidTransferTransactionIds",
                        "finalDecision",
                        "recommendedAction"
                );
        assertThat(toMap(response)).isEqualTo(publicApiFixture("engine-intelligence-full-path-composition-response.json"));
    }

    @Test
    void velocityIsAbsentWhenDisabledAndOptionalFailureRemainsPresentOperationalDiagnostic() {
        TransactionEnrichedEvent enriched = enrichedKafkaRoundTrip(enrichedEventFromRealFeatureCalculator());
        FraudScoringRequest request = FraudScoringRequest.from(enriched);
        RuleBasedFraudScoringEngine baselineEngine = ruleBasedFraudScoringEngine();
        FraudScoreResult baselineResult = baselineEngine.score(request);
        ScoringContext context = new ScoringContext(
                enriched,
                enriched.featureSnapshot(),
                ScoringMode.ML,
                enriched.correlationId(),
                RECEIVED_AT
        );

        EngineIntelligenceResponse withoutVelocity = responseFor(scoredKafkaRoundTrip(new TransactionScoredEventMapper().toEvent(
                request,
                baselineResult,
                Optional.of(summary(evaluate(context, productionEngines(baselineEngine, false))))
        )));
        EngineIntelligenceResponse failedVelocity = responseFor(scoredKafkaRoundTrip(new TransactionScoredEventMapper().toEvent(
                request,
                baselineResult,
                Optional.of(summary(evaluate(context, enginesWithFailingVelocity(baselineEngine))))
        )));

        assertThat(withoutVelocity.status()).isEqualTo(EngineIntelligenceResponseStatus.AVAILABLE);
        assertThat(withoutVelocity.engines()).extracting(engine -> engine.engineId())
                .containsExactly("rules.primary", "ml.python.primary");
        assertThat(withoutVelocity.diagnosticSignals()).extracting(signal -> signal.engineId())
                .doesNotContain("velocity.primary");

        assertThat(failedVelocity.status()).isEqualTo(EngineIntelligenceResponseStatus.DEGRADED);
        assertThat(failedVelocity.engines()).extracting(engine -> engine.engineId())
                .containsExactlyElementsOf(FraudEngineIdentityContract.engineOrder());
        assertThat(failedVelocity.engines()).extracting(engine -> engine.status())
                .containsExactly(
                        EngineIntelligenceEngineStatusResponse.AVAILABLE,
                        EngineIntelligenceEngineStatusResponse.AVAILABLE,
                        EngineIntelligenceEngineStatusResponse.DEGRADED
                );
        assertThat(failedVelocity.engines()).anySatisfy(engine -> {
            assertThat(engine.engineId()).isEqualTo("velocity.primary");
            assertThat(engine.status()).isEqualTo(EngineIntelligenceEngineStatusResponse.DEGRADED);
            assertThat(engine.riskLevel()).isNull();
            assertThat(engine.scoreBucket()).isEqualTo(EngineIntelligenceScoreBucket.UNAVAILABLE);
            assertThat(engine.reasonCodes()).isEmpty();
        });
        assertThat(objectMapper.writeValueAsString(failedVelocity)).doesNotContain("secret token endpoint stacktrace");
        assertThat(failedVelocity.comparison()).isEqualTo(withoutVelocity.comparison());
    }

    private TransactionEnrichedEvent enrichedEventFromRealFeatureCalculator() {
        TransactionRawEvent raw = TransactionFixtures.rawTransaction()
                .withTransactionId("txn-full-path-velocity")
                .withAmount(new BigDecimal("100.00"), "PLN")
                .build();
        EnrichedTransactionFeatures features = new TransactionFeatureCalculator(new CurrencyAmountConverter())
                .calculate(raw, velocityReadySnapshot());
        return new TransactionEnrichedEventMapper().toEvent(raw, features);
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

    private List<FraudSignalEngine> productionEngines(RuleBasedFraudScoringEngine baselineEngine, boolean velocityEnabled) {
        FraudSignalEngine rules = new RuleBasedSignalEngine(new FeatureSnapshotReaderFactory(), baselineEngine);
        FraudSignalEngine ml = pythonMlSignalEngine();
        if (!velocityEnabled) {
            return List.of(rules, ml);
        }
        return List.of(rules, ml, new VelocitySignalEngine(new FeatureSnapshotReaderFactory()));
    }

    private List<FraudSignalEngine> enginesWithFailingVelocity(RuleBasedFraudScoringEngine baselineEngine) {
        return List.of(
                new RuleBasedSignalEngine(new FeatureSnapshotReaderFactory(), baselineEngine),
                pythonMlSignalEngine(),
                new ThrowingVelocitySignalEngine()
        );
    }

    private PythonMlSignalEngine pythonMlSignalEngine() {
        return new PythonMlSignalEngine(new MlFraudScoringEngine(
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
        ));
    }

    private FraudScoringOrchestrationResult evaluate(ScoringContext context, List<FraudSignalEngine> engines) {
        try (FraudScoringOrchestrator orchestrator = new FraudScoringOrchestrator(
                new FraudSignalEngineRegistry(engines),
                FraudScoringOrchestratorExecutionPolicy.defaultInternalPolicy(),
                BoundedFraudEngineExecutor.defaultInternalExecutor(),
                new NoOpFraudScoringOrchestratorMetrics(),
                Clock.fixed(GENERATED_AT, ZoneOffset.UTC)
        )) {
            return orchestrator.evaluate(context);
        }
    }

    private com.frauddetection.common.events.intelligence.EngineIntelligenceSummary summary(
            FraudScoringOrchestrationResult orchestrationResult
    ) {
        return new PublicEngineIntelligenceMapper().map(
                new FraudEngineAggregationService(FraudEngineAggregationPolicy.defaultInternalPolicy())
                        .aggregate(orchestrationResult)
        );
    }

    private EngineIntelligenceResponse responseFor(TransactionScoredEvent event) {
        EngineIntelligenceProjection projection = new EngineIntelligenceProjectionMapper(
                new EngineIntelligenceProjectionPolicy()
        ).map(event.transactionId(), event.engineIntelligence(), null).projection().orElseThrow();
        return new EngineIntelligenceResponseMapper().toResponse(
                new EngineIntelligenceReadModelMapper().map(projection)
        );
    }

    private TransactionEnrichedEvent enrichedKafkaRoundTrip(TransactionEnrichedEvent event) {
        return enrichedDeserializer.deserialize(ENRICHED_TOPIC, enrichedSerializer.serialize(ENRICHED_TOPIC, event));
    }

    private TransactionScoredEvent scoredKafkaRoundTrip(TransactionScoredEvent event) {
        return scoredDeserializer.deserialize(SCORED_TOPIC, scoredSerializer.serialize(SCORED_TOPIC, event));
    }

    private EngineIntelligenceComparison twoEngineComparison(ScoringContext context) {
        return summary(evaluate(context, productionEngines(ruleBasedFraudScoringEngine(), false))).comparison();
    }

    private Map<String, Object> publicApiFixture(String name) throws Exception {
        return objectMapper.readValue(
                repositoryRoot().resolve("contract-fixtures/public-api/" + name).toFile(),
                new TypeReference<>() {
                }
        );
    }

    private Map<String, Object> toMap(Object value) {
        return objectMapper.convertValue(value, new TypeReference<>() {
        });
    }

    private Path repositoryRoot() {
        Path current = Path.of(".").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("contract-fixtures"))
                    && Files.isDirectory(candidate.resolve("alert-service"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("REPOSITORY_ROOT_MISSING");
    }

    private static final class ThrowingVelocitySignalEngine implements FraudSignalEngine {
        @Override
        public FraudSignalEvaluation evaluate(ScoringContext context) {
            throw new IllegalStateException("secret token endpoint stacktrace");
        }

        @Override
        public FraudEngineDescriptor descriptor() {
            return new FraudEngineDescriptor(
                    FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID,
                    FraudEngineType.VELOCITY,
                    "java",
                    "velocity-v1",
                    false
            );
        }
    }
}
