package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.kafka.JacksonKafkaDeserializer;
import com.frauddetection.common.events.kafka.JacksonKafkaSerializer;
import com.frauddetection.common.events.reason.ReasonCode;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.enricher.domain.FeatureStoreSnapshot;
import com.frauddetection.enricher.mapper.TransactionEnrichedEventMapper;
import com.frauddetection.enricher.service.CurrencyAmountConverter;
import com.frauddetection.enricher.service.TransactionFeatureCalculator;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.engine.velocity.VelocitySignalEngine;
import com.frauddetection.scoring.features.FeatureSnapshotReaderFactory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class FeatureEnricherRulesCompositionTest {
    private static final Instant RECEIVED_AT = Instant.parse("2026-05-30T10:00:00Z");

    private final TransactionFeatureCalculator calculator = new TransactionFeatureCalculator(new CurrencyAmountConverter());
    private final TransactionEnrichedEventMapper mapper = new TransactionEnrichedEventMapper();
    private final RuleBasedFraudScoringEngine rules = new RuleBasedFraudScoringEngine(
            new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED)
    );
    private final JacksonKafkaSerializer<TransactionEnrichedEvent> serializer = new JacksonKafkaSerializer<>();
    private final JacksonKafkaDeserializer<TransactionEnrichedEvent> deserializer =
            new JacksonKafkaDeserializer<>(TransactionEnrichedEvent.class);

    @Test
    void officialCountFiveProducerOutputPreservesRulesV2MediumScoreWithoutFeatureFlags() {
        TransactionEnrichedEvent enriched = officialEvent(4, BigDecimal.ZERO, new BigDecimal("100.00"));

        FraudScoreResult result = score(enriched);

        assertThat(enriched.recentTransactionCount()).isEqualTo(5);
        assertThat(enriched.transactionVelocityPerMinute()).isEqualTo(5.0d);
        assertThat(result.fraudScore()).isCloseTo(0.47d, within(0.000001d));
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(result.alertRecommended()).isFalse();
        assertThat(result.reasonCodes()).containsExactly(FraudFeatureContract.FLAG_HIGH_VELOCITY);
    }

    @Test
    void officialRapidTransferProducerOutputPreservesRulesV2CriticalAlertFromCanonicalFacts() {
        TransactionEnrichedEvent enriched = officialEvent(1, new BigDecimal("10000.00"), new BigDecimal("10000.00"));

        FraudScoreResult result = score(enriched);
        assertThat(enriched.featureSnapshot()).doesNotContainKeys(
                "featureFlags",
                "rapidTransferFraudCaseCandidate",
                FraudFeatureContract.RAPID_TRANSFER_THRESHOLD_PLN,
                FraudFeatureContract.RAPID_TRANSFER_COUNT,
                FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN,
                FraudFeatureContract.RAPID_TRANSFER_WINDOW
        );
        assertThat(result.fraudScore()).isCloseTo(0.94d, within(0.000001d));
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(result.alertRecommended()).isTrue();
        assertThat(result.reasonCodes()).containsExactly(
                FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY,
                FraudFeatureContract.FLAG_RAPID_PLN_20K_BURST,
                ReasonCode.HIGH_TRANSACTION_AMOUNT.wireValue()
        );
    }

    @Test
    void velocityDiagnosticEvaluationDoesNotMutateRulesResult() {
        TransactionEnrichedEvent enriched = officialEvent(1, new BigDecimal("10000.00"), new BigDecimal("10000.00"));
        FraudScoreResult beforeDiagnosticRuntime = score(enriched);

        var velocity = new VelocitySignalEngine(new FeatureSnapshotReaderFactory()).evaluate(new ScoringContext(
                enriched,
                enriched.featureSnapshot(),
                ScoringMode.RULE_BASED,
                enriched.correlationId(),
                RECEIVED_AT
        ));
        FraudScoreResult afterDiagnosticRuntime = score(enriched);

        assertThat(velocity.reasonCodes()).contains(FraudFeatureContract.FLAG_RAPID_PLN_20K_BURST);
        assertThat(afterDiagnosticRuntime.fraudScore())
                .isCloseTo(beforeDiagnosticRuntime.fraudScore(), within(0.000001d));
        assertThat(afterDiagnosticRuntime.riskLevel()).isEqualTo(beforeDiagnosticRuntime.riskLevel());
        assertThat(afterDiagnosticRuntime.alertRecommended()).isEqualTo(beforeDiagnosticRuntime.alertRecommended());
        assertThat(afterDiagnosticRuntime.reasonCodes()).containsExactlyElementsOf(beforeDiagnosticRuntime.reasonCodes());
    }

    @Test
    void officialFeatureEnricherKafkaReplayFeedsRulesAndVelocityWithTheSameNormalizedSnapshot() {
        TransactionEnrichedEvent produced = officialEvent(4, BigDecimal.ZERO, new BigDecimal("100.00"));
        TransactionEnrichedEvent replayed = kafkaReplay(produced);
        Map<String, Object> replayedSnapshot = replayed.featureSnapshot();

        FraudScoreResult rulesResult = score(replayed);
        var velocity = new VelocitySignalEngine(new FeatureSnapshotReaderFactory()).evaluate(new ScoringContext(
                replayed,
                replayedSnapshot,
                ScoringMode.RULE_BASED,
                replayed.correlationId(),
                RECEIVED_AT
        ));

        assertThat(replayedSnapshot.get(FraudFeatureContract.RECENT_TRANSACTION_COUNT))
                .isEqualTo(5)
                .isExactlyInstanceOf(Integer.class);
        assertThat(replayedSnapshot.get(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE))
                .isEqualTo(5.0d)
                .isExactlyInstanceOf(Double.class);
        assertThat(replayedSnapshot.get(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN))
                .isEqualTo(new BigDecimal("100.00"))
                .isExactlyInstanceOf(BigDecimal.class);
        assertThat(rulesResult.featureSnapshot())
                .containsEntry(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5)
                .containsEntry(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 5.0d)
                .containsEntry(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("100.00"))
                .containsEntry(FraudFeatureContract.CURRENCY, "PLN")
                .doesNotContainKeys(
                        "featureFlags",
                        "rapidTransferFraudCaseCandidate",
                        FraudFeatureContract.RAPID_TRANSFER_THRESHOLD_PLN,
                        FraudFeatureContract.RAPID_TRANSFER_COUNT,
                        FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN,
                        FraudFeatureContract.RAPID_TRANSFER_WINDOW
                );
        assertThat(rulesResult.fraudScore()).isCloseTo(0.47d, within(0.000001d));
        assertThat(rulesResult.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(velocity.reasonCodes()).containsExactly("TRANSACTION_VELOCITY");
    }

    private TransactionEnrichedEvent officialEvent(int previousCount, BigDecimal previousAmountPln, BigDecimal currentAmountPln) {
        var raw = TransactionFixtures.rawTransaction()
                .withAmount(currentAmountPln, "PLN")
                .build();
        var snapshot = new FeatureStoreSnapshot(
                previousCount,
                previousAmountPln,
                previousAmountPln,
                List.of(),
                0,
                Instant.parse("2026-04-20T10:12:00Z"),
                true
        );
        return mapper.toEvent(raw, calculator.calculate(raw, snapshot));
    }

    private TransactionEnrichedEvent kafkaReplay(TransactionEnrichedEvent event) {
        byte[] bytes = serializer.serialize("transactions.enriched", event);
        return deserializer.deserialize("transactions.enriched", bytes);
    }

    private FraudScoreResult score(TransactionEnrichedEvent event) {
        return rules.score(FraudScoringRequest.from(event));
    }
}
