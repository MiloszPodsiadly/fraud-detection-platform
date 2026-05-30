package com.frauddetection.scoring.engine.rules;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.common.events.reason.ReasonCode;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.context.ScoringContext;
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
            new RuleBasedSignalEngine(new FeatureSnapshotReaderFactory());

    @Test
    void baselineScenarioRemainsLowAndNoSignalInAdapter() {
        TransactionEnrichedEvent event = event(false, false, false, 1, 0.1d, BigDecimal.TEN, List.of(),
                Map.of(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 1));

        var production = productionEngine.score(FraudScoringRequest.from(event));
        var adapterResult = adapter.evaluate(context(event));

        assertThat(production.riskLevel().name()).isEqualTo(adapterResult.riskLevel().name());
        assertThat(adapterResult.reasonCodes()).isEmpty();
        assertThat(adapterResult.evidence()).isEmpty();
    }

    @Test
    void deviceNoveltyScenarioAlignsAtSignalIntentLevel() {
        TransactionEnrichedEvent event = event(true, false, false, 1, 0.1d, BigDecimal.TEN, List.of(),
                Map.of(FraudFeatureContract.DEVICE_NOVELTY, true));

        assertParity(event, ReasonCode.DEVICE_NOVELTY.wireValue(),
                RuleBasedSignalReasonCode.DEVICE_NOVELTY_SIGNAL.wireValue());
    }

    @Test
    void countryMismatchScenarioAlignsAtSignalIntentLevel() {
        TransactionEnrichedEvent event = event(false, true, false, 1, 0.1d, BigDecimal.TEN, List.of(),
                Map.of(FraudFeatureContract.COUNTRY_MISMATCH, true));

        assertParity(event, ReasonCode.COUNTRY_MISMATCH.wireValue(),
                RuleBasedSignalReasonCode.COUNTRY_MISMATCH_SIGNAL.wireValue());
    }

    @Test
    void proxyVpnScenarioAlignsAtSignalIntentLevel() {
        TransactionEnrichedEvent event = event(false, false, true, 1, 0.1d, BigDecimal.TEN, List.of(),
                Map.of(FraudFeatureContract.PROXY_OR_VPN_DETECTED, true));

        assertParity(event, ReasonCode.PROXY_OR_VPN.wireValue(),
                RuleBasedSignalReasonCode.PROXY_OR_VPN_SIGNAL.wireValue());
    }

    @Test
    void rapidTransferBurstScenarioAlignsAtSignalIntentLevel() {
        TransactionEnrichedEvent event = event(false, false, false, 1, 0.1d, BigDecimal.TEN,
                List.of(ReasonCode.RAPID_PLN_20K_BURST.wireValue()),
                Map.of(FraudFeatureContract.RAPID_TRANSFER_BURST, true));

        assertParity(event, ReasonCode.RAPID_PLN_20K_BURST.wireValue(),
                RuleBasedSignalReasonCode.RAPID_TRANSFER_BURST_SIGNAL.wireValue());
    }

    @Test
    void velocityThresholdScenarioAlignsAtSignalIntentLevel() {
        TransactionEnrichedEvent event = event(false, false, false, 6, 5.0d, BigDecimal.TEN, List.of(),
                Map.of(
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT, 6,
                        FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 5.0d
                ));

        assertParity(event, ReasonCode.TRANSACTION_VELOCITY.wireValue(),
                RuleBasedSignalReasonCode.VELOCITY_THRESHOLD_EXCEEDED.wireValue());
    }

    @Test
    void highRiskFlagsScenarioIsAdapterCoveredWithoutMakingAdapterSourceOfTruth() {
        TransactionEnrichedEvent event = event(false, false, false, 1, 0.1d, BigDecimal.TEN,
                List.of(ReasonCode.HIGH_AMOUNT_ACTIVITY.wireValue()),
                Map.of(FraudFeatureContract.HIGH_RISK_FLAG_COUNT, 2));

        var production = productionEngine.score(FraudScoringRequest.from(event));
        var adapterResult = adapter.evaluate(context(event));

        assertThat(production.reasonCodes()).contains(ReasonCode.HIGH_AMOUNT_ACTIVITY.wireValue());
        assertThat(adapterResult.reasonCodes()).contains(RuleBasedSignalReasonCode.HIGH_RISK_FLAGS_PRESENT.wireValue());
        assertThat(adapterResult.engineId()).isEqualTo("rules.primary");
    }

    @Test
    void multipleSignalsCombineWithoutRawEvidenceExposure() {
        TransactionEnrichedEvent event = event(true, true, true, 7, 7.0d, new BigDecimal("6400.00"),
                List.of(
                        ReasonCode.DEVICE_NOVELTY.wireValue(),
                        ReasonCode.COUNTRY_MISMATCH.wireValue(),
                        ReasonCode.PROXY_OR_VPN.wireValue(),
                        ReasonCode.HIGH_VELOCITY.wireValue(),
                        ReasonCode.RAPID_PLN_20K_BURST.wireValue()
                ),
                Map.of(
                        FraudFeatureContract.DEVICE_NOVELTY, true,
                        FraudFeatureContract.COUNTRY_MISMATCH, true,
                        FraudFeatureContract.PROXY_OR_VPN_DETECTED, true,
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT, 7,
                        FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 7.0d,
                        FraudFeatureContract.RAPID_TRANSFER_BURST, true
                ));

        var production = productionEngine.score(FraudScoringRequest.from(event));
        var adapterResult = adapter.evaluate(context(event));

        assertThat(production.alertRecommended()).isTrue();
        assertThat(adapterResult.riskLevel()).isIn(
                com.frauddetection.common.events.enums.RiskLevel.HIGH,
                com.frauddetection.common.events.enums.RiskLevel.CRITICAL
        );
        assertThat(adapterResult.reasonCodes()).contains(
                RuleBasedSignalReasonCode.DEVICE_NOVELTY_SIGNAL.wireValue(),
                RuleBasedSignalReasonCode.COUNTRY_MISMATCH_SIGNAL.wireValue(),
                RuleBasedSignalReasonCode.PROXY_OR_VPN_SIGNAL.wireValue(),
                RuleBasedSignalReasonCode.VELOCITY_THRESHOLD_EXCEEDED.wireValue(),
                RuleBasedSignalReasonCode.RAPID_TRANSFER_BURST_SIGNAL.wireValue()
        );
        assertThat(adapterResult.evidence().toString()).doesNotContain("6400.00", "acct-", "cust-");
    }

    private void assertParity(TransactionEnrichedEvent event, String productionReason, String adapterReason) {
        var production = productionEngine.score(FraudScoringRequest.from(event));
        var adapterResult = adapter.evaluate(context(event));

        assertThat(production.reasonCodes()).contains(productionReason);
        assertThat(adapterResult.reasonCodes()).contains(adapterReason);
        assertThat(adapterResult.evidence().toString()).doesNotContain("acct-", "cust-", "raw");
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
            BigDecimal recentAmount,
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
                new Money(recentAmount, "PLN"),
                base.merchantInfo(),
                base.deviceInfo(),
                base.locationInfo(),
                base.customerContext(),
                recentTransactionCount,
                "PT1M",
                new Money(recentAmount, "PLN"),
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
