package com.frauddetection.scoring.service;

import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedFraudScoringEngineTest {

    private final RuleBasedFraudScoringEngine engine = new RuleBasedFraudScoringEngine(new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED));

    @Test
    void shouldProduceCriticalRiskForStrongFraudSignals() {
        var event = new com.frauddetection.common.events.contract.TransactionEnrichedEvent(
                java.util.UUID.randomUUID().toString(),
                "txn-9001",
                "corr-9001",
                "cust-9001",
                "acct-9001",
                java.time.Instant.now(),
                java.time.Instant.now(),
                new com.frauddetection.common.events.model.Money(new BigDecimal("1499.99"), "USD"),
                TransactionFixtures.enrichedTransaction().build().merchantInfo(),
                TransactionFixtures.enrichedTransaction().build().deviceInfo(),
                TransactionFixtures.enrichedTransaction().build().locationInfo(),
                TransactionFixtures.enrichedTransaction().build().customerContext(),
                7,
                "PT1M",
                new com.frauddetection.common.events.model.Money(new BigDecimal("6400.00"), "USD"),
                "PT1M",
                7.0d,
                7,
                true,
                true,
                true,
                List.of("DEVICE_NOVELTY", "COUNTRY_MISMATCH", "PROXY_OR_VPN", "HIGH_VELOCITY", "HIGH_AMOUNT_ACTIVITY"),
                Map.of("recentTransactionCount", 7)
        );

        var result = engine.score(FraudScoringRequest.from(event));

        assertThat(result.fraudScore()).isGreaterThanOrEqualTo(0.90d);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(result.reasonCodes()).contains("DEVICE_NOVELTY", "COUNTRY_MISMATCH", "TRANSACTION_VELOCITY", "HIGH_TRANSACTION_AMOUNT");
        assertThat(result.modelName()).isEqualTo("rule-based-engine");
        assertThat(result.modelVersion()).isEqualTo("v1");
        assertThat(result.inferenceTimestamp()).isNotNull();
        assertThat(result.featureSnapshot()).isEqualTo(event.featureSnapshot());
        assertThat(result.explanationMetadata()).containsEntry("explanationType", "WEIGHTED_REASON_CODES");
        assertThat(result.scoreDetails()).containsKey("explanationMetadata");
        assertThat(result.alertRecommended()).isTrue();
    }

    @Test
    void shouldKeepLowRiskForBaselineTraffic() {
        var event = new com.frauddetection.common.events.contract.TransactionEnrichedEvent(
                java.util.UUID.randomUUID().toString(),
                "txn-1002",
                "corr-1002",
                "cust-1002",
                "acct-1002",
                java.time.Instant.now(),
                java.time.Instant.now(),
                new com.frauddetection.common.events.model.Money(new BigDecimal("45.50"), "USD"),
                TransactionFixtures.enrichedTransaction().build().merchantInfo(),
                TransactionFixtures.enrichedTransaction().build().deviceInfo(),
                TransactionFixtures.enrichedTransaction().build().locationInfo(),
                TransactionFixtures.enrichedTransaction().build().customerContext(),
                1,
                "PT1M",
                new com.frauddetection.common.events.model.Money(new BigDecimal("45.50"), "USD"),
                "PT1M",
                0.06d,
                1,
                false,
                false,
                false,
                List.of(),
                Map.of("recentTransactionCount", 1)
        );

        var result = engine.score(FraudScoringRequest.from(event));

        assertThat(result.fraudScore()).isLessThan(0.45d);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.alertRecommended()).isFalse();
    }

    @Test
    void shouldKeepSingleHighAmountTransactionLowWhileKeepingDiagnosticReason() {
        var event = new com.frauddetection.common.events.contract.TransactionEnrichedEvent(
                java.util.UUID.randomUUID().toString(),
                "txn-amount-only",
                "corr-amount-only",
                "cust-amount-only",
                "acct-amount-only",
                java.time.Instant.now(),
                java.time.Instant.now(),
                new com.frauddetection.common.events.model.Money(new BigDecimal("10000.00"), "PLN"),
                TransactionFixtures.enrichedTransaction().build().merchantInfo(),
                TransactionFixtures.enrichedTransaction().build().deviceInfo(),
                TransactionFixtures.enrichedTransaction().build().locationInfo(),
                TransactionFixtures.enrichedTransaction().build().customerContext(),
                1,
                "PT1M",
                new com.frauddetection.common.events.model.Money(new BigDecimal("10000.00"), "PLN"),
                "PT1M",
                1.0d,
                1,
                false,
                false,
                false,
                List.of(),
                Map.of("recentTransactionCount", 1)
        );

        var result = engine.score(FraudScoringRequest.from(event));

        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.reasonCodes()).contains("HIGH_TRANSACTION_AMOUNT");
        assertThat(result.alertRecommended()).isFalse();
    }
}
