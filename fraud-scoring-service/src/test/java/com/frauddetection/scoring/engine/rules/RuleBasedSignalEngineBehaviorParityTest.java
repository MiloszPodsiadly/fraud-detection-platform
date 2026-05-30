package com.frauddetection.scoring.engine.rules;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedSignalEngineBehaviorParityTest {

    private final RuleBasedFraudScoringEngine productionEngine =
            new RuleBasedFraudScoringEngine(new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED));
    private final RuleBasedSignalEngine adapter =
            new RuleBasedSignalEngine(new FeatureSnapshotReaderFactory(), productionEngine);

    @Test
    void baselineScenarioMapsProductionScoreRiskAndReasonsExactly() {
        TransactionEnrichedEvent event = event(false, false, false, 1, 0.1d, BigDecimal.TEN, List.of(), Map.of());

        assertProductionMappingParity(event);
    }

    @Test
    void featureFlagAndEventBooleanDedupeReasonButPreserveProductionScore() {
        TransactionEnrichedEvent event = event(true, false, false, 1, 0.1d, BigDecimal.TEN,
                List.of(ReasonCode.DEVICE_NOVELTY.wireValue()),
                Map.of(FraudFeatureContract.DEVICE_NOVELTY, true));

        FraudScoreResult production = assertProductionMappingParity(event);

        assertThat(production.reasonCodes()).containsExactly(ReasonCode.DEVICE_NOVELTY.wireValue());
        assertThat(production.fraudScore()).isGreaterThan(0.32d).isLessThan(0.34d);
    }

    @Test
    void thresholdBoundaryRiskAndAlertRecommendationMirrorProduction() {
        TransactionEnrichedEvent event = event(true, true, true, 5, 5.0d, BigDecimal.TEN,
                List.of(ReasonCode.HIGH_VELOCITY.wireValue()),
                Map.of(
                        FraudFeatureContract.DEVICE_NOVELTY, true,
                        FraudFeatureContract.COUNTRY_MISMATCH, true,
                        FraudFeatureContract.PROXY_OR_VPN_DETECTED, true,
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5,
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
                List.of(),
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
    void rapidTransferFraudCaseCandidateMirrorsProductionSnapshotSignal() {
        TransactionEnrichedEvent event = event(false, false, false, 1, 0.1d, BigDecimal.TEN,
                List.of(),
                Map.of(FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE, true));

        FraudScoreResult production = assertProductionMappingParity(event);

        assertThat(production.reasonCodes()).containsExactly(ReasonCode.RAPID_TRANSFER_FRAUD_CASE.wireValue());
    }

    @Test
    void rapidTransferSignalsKeepDistinctMappedEvidenceAndContributions() {
        TransactionEnrichedEvent event = event(false, false, false, 1, 0.1d, BigDecimal.TEN,
                List.of(ReasonCode.RAPID_PLN_20K_BURST.wireValue()),
                Map.of(
                        FraudFeatureContract.RAPID_TRANSFER_BURST, true,
                        FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE, true
                ));

        FraudScoreResult production = assertProductionMappingParity(event);
        var adapterResult = adapter.evaluate(context(event));

        assertThat(production.reasonCodes()).containsExactly(
                ReasonCode.RAPID_PLN_20K_BURST.wireValue(),
                ReasonCode.RAPID_TRANSFER_FRAUD_CASE.wireValue()
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
            List<String> featureFlags,
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
                recentTransactionCount,
                "PT1M",
                new Money(amount, "PLN"),
                "PT1M",
                velocityPerMinute,
                base.merchantFrequency7d(),
                deviceNovelty,
                countryMismatch,
                proxyOrVpn,
                featureFlags,
                featureSnapshot
        );
    }
}
