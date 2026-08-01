package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.features.FraudFeatureContract;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class FeatureEnricherRulesCompositionTest {
    private static final Instant RECEIVED_AT = Instant.parse("2026-05-30T10:00:00Z");

    private final TransactionFeatureCalculator calculator = new TransactionFeatureCalculator(new CurrencyAmountConverter());
    private final TransactionEnrichedEventMapper mapper = new TransactionEnrichedEventMapper();
    private final RuleBasedFraudScoringEngine rules = new RuleBasedFraudScoringEngine(
            new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED)
    );

    @Test
    void officialCountFiveProducerOutputPreservesRulesV1MediumScoreWithoutHighVelocityFlag() {
        TransactionEnrichedEvent enriched = officialEvent(4, BigDecimal.ZERO, new BigDecimal("100.00"));

        FraudScoreResult result = score(enriched);

        assertThat(enriched.recentTransactionCount()).isEqualTo(5);
        assertThat(enriched.transactionVelocityPerMinute()).isEqualTo(5.0d);
        assertThat(enriched.featureFlags()).doesNotContain(FraudFeatureContract.FLAG_HIGH_VELOCITY);
        assertThat(result.fraudScore()).isCloseTo(0.47d, within(0.000001d));
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(result.alertRecommended()).isFalse();
        assertThat(result.reasonCodes()).containsExactly(FraudFeatureContract.FLAG_HIGH_VELOCITY);
    }

    @Test
    void legacyHighVelocityFlagDoesNotChangeCanonicalProducerScore() {
        TransactionEnrichedEvent canonical = officialEvent(4, BigDecimal.ZERO, new BigDecimal("100.00"));
        TransactionEnrichedEvent legacy = withAdditionalFlag(canonical, FraudFeatureContract.FLAG_HIGH_VELOCITY);

        assertSameCoreResult(legacy, canonical);
    }

    @Test
    void officialRapidTransferProducerOutputPreservesRulesV1CriticalAlert() {
        TransactionEnrichedEvent enriched = officialEvent(1, new BigDecimal("10000.00"), new BigDecimal("10000.00"));

        FraudScoreResult result = score(enriched);

        assertThat(enriched.featureFlags()).contains(
                FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY,
                FraudFeatureContract.FLAG_RAPID_PLN_20K_BURST
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

    private TransactionEnrichedEvent withAdditionalFlag(TransactionEnrichedEvent source, String flag) {
        List<String> flags = new ArrayList<>(source.featureFlags());
        if (!flags.contains(flag)) {
            flags.add(flag);
        }
        return new TransactionEnrichedEvent(
                source.eventId(),
                source.transactionId(),
                source.correlationId(),
                source.customerId(),
                source.accountId(),
                source.createdAt(),
                source.transactionTimestamp(),
                source.transactionAmount(),
                source.merchantInfo(),
                source.deviceInfo(),
                source.locationInfo(),
                source.customerContext(),
                source.recentTransactionCount(),
                source.recentTransactionCountWindow(),
                source.recentAmountSum(),
                source.recentAmountSumWindow(),
                source.transactionVelocityPerMinute(),
                source.merchantFrequency7d(),
                source.deviceNovelty(),
                source.countryMismatch(),
                source.proxyOrVpnDetected(),
                List.copyOf(flags),
                source.featureSnapshot()
        );
    }

    private void assertSameCoreResult(TransactionEnrichedEvent left, TransactionEnrichedEvent right) {
        FraudScoreResult leftResult = score(left);
        FraudScoreResult rightResult = score(right);

        assertThat(leftResult.fraudScore()).isEqualTo(rightResult.fraudScore());
        assertThat(leftResult.riskLevel()).isEqualTo(rightResult.riskLevel());
        assertThat(leftResult.alertRecommended()).isEqualTo(rightResult.alertRecommended());
        assertThat(leftResult.reasonCodes()).containsExactlyElementsOf(rightResult.reasonCodes());
    }

    private FraudScoreResult score(TransactionEnrichedEvent event) {
        return rules.score(FraudScoringRequest.from(event));
    }
}
