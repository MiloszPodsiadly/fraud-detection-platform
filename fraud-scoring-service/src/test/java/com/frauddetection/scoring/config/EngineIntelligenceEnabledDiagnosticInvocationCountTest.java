package com.frauddetection.scoring.config;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionService;
import com.frauddetection.scoring.service.MlFraudScoringEngine;
import com.frauddetection.scoring.service.RuleBasedFraudScoringEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.frauddetection.scoring.config.EngineIntelligenceSpringContextTestSupport.enabledContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EngineIntelligenceEnabledDiagnosticInvocationCountTest {

    @Test
    void enabledFlagRunsAdditionalRulesAndMlDiagnosticWorkOnce() {
        enabledContextRunner().run(context -> {
            RuleBasedFraudScoringEngine rules = context.getBean(RuleBasedFraudScoringEngine.class);
            MlFraudScoringEngine ml = context.getBean(MlFraudScoringEngine.class);
            when(rules.scoreValidated(any())).thenReturn(result(0.15d, RiskLevel.LOW, Map.of()));
            when(ml.score(any())).thenReturn(result(0.91d, RiskLevel.CRITICAL, Map.of("modelAvailable", true)));

            var summary = context.getBean(EngineIntelligenceEmissionService.class)
                    .emitIfEnabled(FraudScoringRequest.from(validRulesInput()));

            assertThat(summary).isPresent();
            verify(rules, times(1)).scoreValidated(any());
            verify(ml, times(1)).score(any());
        });
    }

    private TransactionEnrichedEvent validRulesInput() {
        TransactionEnrichedEvent base = TransactionFixtures.enrichedTransaction().build();
        return new TransactionEnrichedEvent(
                base.eventId(),
                base.transactionId(),
                base.correlationId(),
                base.customerId(),
                base.accountId(),
                base.createdAt(),
                base.transactionTimestamp(),
                new Money(new BigDecimal("100.00"), "PLN"),
                base.merchantInfo(),
                base.deviceInfo(),
                base.locationInfo(),
                base.customerContext(),
                1,
                "PT1M",
                null,
                null,
                1.0d,
                base.merchantFrequency7d(),
                false,
                false,
                false,
                Map.ofEntries(
                        Map.entry(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 1),
                        Map.entry(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M"),
                        Map.entry(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 1.0d),
                        Map.entry(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("100.00")),
                        Map.entry(FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M"),
                        Map.entry(FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN, new BigDecimal("100.00")),
                        Map.entry(FraudFeatureContract.MERCHANT_FREQUENCY_7D, base.merchantFrequency7d()),
                        Map.entry(FraudFeatureContract.DEVICE_NOVELTY, false),
                        Map.entry(FraudFeatureContract.COUNTRY_MISMATCH, false),
                        Map.entry(FraudFeatureContract.PROXY_OR_VPN_DETECTED, false),
                        Map.entry(FraudFeatureContract.CURRENCY, "PLN")
                )
        );
    }

    private FraudScoreResult result(double score, RiskLevel riskLevel, Map<String, Object> explanationMetadata) {
        return new FraudScoreResult(
                score,
                riskLevel,
                "DIAGNOSTIC_TEST",
                "test-engine",
                "v1",
                Instant.parse("2026-05-31T10:00:00Z"),
                List.of(),
                Map.of(),
                Map.of(),
                explanationMetadata,
                false
        );
    }
}
