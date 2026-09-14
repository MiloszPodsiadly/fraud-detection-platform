package com.frauddetection.scoring.engine.rules;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.common.events.reason.ReasonCode;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.features.FeatureSnapshotReaderFactory;
import com.frauddetection.scoring.service.RuleBasedFraudScoringEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedSignalEngineBehaviorParityTest {

    private final RuleBasedFraudScoringEngine productionEngine =
            new RuleBasedFraudScoringEngine(new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED));
    private final RuleBasedSignalEngine adapter =
            new RuleBasedSignalEngine(new FeatureSnapshotReaderFactory(), productionEngine);

    @Test
    void baselineScenarioMapsProductionScoreRiskAndReasonsExactly() {
        TransactionEnrichedEvent event = event(false, false, false, 1, 0.1d, BigDecimal.TEN, Map.of());

        assertProductionMappingParity(event);
    }

    @Test
    void canonicalBooleanReasonPreservesProductionScore() {
        TransactionEnrichedEvent event = event(true, false, false, 1, 0.1d, BigDecimal.TEN,
                Map.of(FraudFeatureContract.DEVICE_NOVELTY, true));

        FraudScoreResult production = assertProductionMappingParity(event);

        assertThat(production.reasonCodes()).containsExactly(ReasonCode.DEVICE_NOVELTY.wireValue());
        assertThat(production.fraudScore()).isEqualTo(0.23d);
    }

    @Test
    void consolidatedRulesV2ThresholdRiskAndAlertRecommendationMirrorProduction() {
        TransactionEnrichedEvent event = event(true, true, false, 5, 5.0d, BigDecimal.TEN,
                Map.of(
                        FraudFeatureContract.DEVICE_NOVELTY, true,
                        FraudFeatureContract.COUNTRY_MISMATCH, true,
                        FraudFeatureContract.PROXY_OR_VPN_DETECTED, false,
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5,
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M",
                        FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 5.0d
                ));

        FraudScoreResult production = assertProductionMappingParity(event);
        var adapterResult = adapter.evaluate(context(event));

        assertThat(production.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(production.alertRecommended()).isEqualTo(alertRecommended(adapterResult.riskLevel()));
    }

    @Test
    void highAmountDiagnosticReasonIsMappedWithoutAdapterLocalZeroWeightSignal() {
        TransactionEnrichedEvent event = event(false, false, false, 1, 0.1d, new BigDecimal("1500.00"),
                Map.of(FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN, new BigDecimal("1500.00")));

        FraudScoreResult production = assertProductionMappingParity(event);
        var adapterResult = adapter.evaluate(context(event));

        assertThat(production.reasonCodes()).containsExactly(ReasonCode.HIGH_TRANSACTION_AMOUNT.wireValue());
        assertThat(adapterResult.contributions()).singleElement().satisfies(contribution -> {
            assertThat(contribution.feature()).isEqualTo(ReasonCode.HIGH_TRANSACTION_AMOUNT.wireValue());
            assertThat(contribution.weight()).isNull();
        });
    }

    @Test
    void rapidTransferSignalsKeepSingleMappedEvidenceAndContributionForOneFact() {
        TransactionEnrichedEvent event = event(false, false, false, 2, 2.0d, new BigDecimal("20000.00"),
                Map.of(
                        FraudFeatureContract.RAPID_TRANSFER_COUNT, 2,
                        FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN, new BigDecimal("20000.00"),
                        FraudFeatureContract.RAPID_TRANSFER_WINDOW, "PT1M"
                ));

        FraudScoreResult production = assertProductionMappingParity(event);
        var adapterResult = adapter.evaluate(context(event));

        assertThat(production.reasonCodes()).containsExactly(
                ReasonCode.HIGH_AMOUNT_ACTIVITY.wireValue(),
                ReasonCode.RAPID_PLN_20K_BURST.wireValue(),
                ReasonCode.HIGH_TRANSACTION_AMOUNT.wireValue()
        );
        assertThat(adapterResult.contributions()).extracting(contribution -> contribution.feature())
                .containsExactlyElementsOf(production.reasonCodes());
        assertThat(adapterResult.evidence()).extracting(evidence -> evidence.reasonCode())
                .containsExactlyElementsOf(production.reasonCodes());
        assertThat(adapterResult.evidence().toString()).doesNotContain("acct-", "cust-", "raw");
    }

    private FraudScoreResult assertProductionMappingParity(TransactionEnrichedEvent event) {
        FraudScoreResult production = productionEngine.score(FraudScoringRequest.from(event));
        var adapterResult = adapter.evaluate(context(event));

        assertThat(adapterResult.score()).isEqualTo(production.fraudScore());
        assertThat(adapterResult.riskLevel()).isEqualTo(production.riskLevel());
        assertThat(adapterResult.reasonCodes()).containsExactlyElementsOf(production.reasonCodes());
        assertThat(adapterResult.modelName()).isEqualTo(production.modelName());
        assertThat(adapterResult.modelVersion()).isEqualTo(production.modelVersion());
        assertThat(adapterResult.confidence()).isEqualTo(FraudEngineConfidence.UNKNOWN);
        assertThat(alertRecommended(adapterResult.riskLevel())).isEqualTo(production.alertRecommended());
        return production;
    }

    private boolean alertRecommended(RiskLevel riskLevel) {
        return riskLevel == RiskLevel.HIGH || riskLevel == RiskLevel.CRITICAL;
    }

    private ScoringContext context(TransactionEnrichedEvent event) {
        return new ScoringContext(
                event,
                event.featureSnapshot(),
                ScoringMode.RULE_BASED,
                event.correlationId(),
                Instant.parse("2026-05-30T10:00:00Z")
        );
    }

    private TransactionEnrichedEvent event(
            boolean deviceNovelty,
            boolean countryMismatch,
            boolean proxyOrVpn,
            int recentTransactionCount,
            double velocityPerMinute,
            BigDecimal amount,
            Map<String, Object> featureSnapshot
    ) {
        TransactionEnrichedEvent base = TransactionFixtures.enrichedTransaction().build();
        return new TransactionEnrichedEvent(
                base.eventId(),
                base.transactionId(),
                base.correlationId(),
                base.customerId(),
                base.accountId(),
                base.createdAt(),
                base.transactionTimestamp(),
                new Money(amount, "PLN"),
                base.merchantInfo(),
                base.deviceInfo(),
                base.locationInfo(),
                base.customerContext(),
                canonicalSnapshot(
                        deviceNovelty,
                        countryMismatch,
                        proxyOrVpn,
                        recentTransactionCount,
                        amount,
                        featureSnapshot
                )
        );
    }

    private Map<String, Object> canonicalSnapshot(
            boolean deviceNovelty,
            boolean countryMismatch,
            boolean proxyOrVpn,
            int recentTransactionCount,
            BigDecimal amount,
            Map<String, Object> overrides
    ) {
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT, recentTransactionCount);
        snapshot.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M");
        snapshot.put(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, (double) recentTransactionCount);
        snapshot.put(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, amount);
        snapshot.put(FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M");
        snapshot.put(FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN, amount);
        snapshot.put(FraudFeatureContract.MERCHANT_FREQUENCY_7D, 1);
        snapshot.put(FraudFeatureContract.DEVICE_NOVELTY, deviceNovelty);
        snapshot.put(FraudFeatureContract.COUNTRY_MISMATCH, countryMismatch);
        snapshot.put(FraudFeatureContract.PROXY_OR_VPN_DETECTED, proxyOrVpn);
        snapshot.put(FraudFeatureContract.CURRENCY, "PLN");
        snapshot.putAll(overrides);
        return Map.copyOf(snapshot);
    }
}
