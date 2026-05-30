package com.frauddetection.scoring.engine.rules;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.engine.FraudEngineEvidence;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.common.events.reason.ReasonCode;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.features.FeatureSnapshotReaderFactory;
import com.frauddetection.scoring.service.RuleBasedFraudScoringEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedSignalEngineEvidenceSafetyTest {

    private final RuleBasedFraudScoringEngine productionEngine =
            new RuleBasedFraudScoringEngine(new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED));
    private final RuleBasedSignalEngine adapter =
            new RuleBasedSignalEngine(new FeatureSnapshotReaderFactory(), productionEngine);

    @Test
    void resultExposesOnlyBoundedReasonCodesAndSafeIdentifiers() {
        FraudEngineResult result = adapter.evaluate(context(event(Map.of(
                FraudFeatureContract.DEVICE_NOVELTY, true,
                FraudFeatureContract.CUSTOMER_SEGMENT, "VIP",
                FraudFeatureContract.MERCHANT_CATEGORY, "crypto",
                FraudFeatureContract.CURRENCY, "EUR",
                FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN, new BigDecimal("50000"),
                FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN, new BigDecimal("99999"),
                FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, List.of("tx-secret-1"),
                FraudFeatureContract.FEATURE_FLAGS, List.of("SENSITIVE_FLAG")
        ))));

        String flattened = flatten(result);
        assertThat(flattened)
                .doesNotContain("VIP")
                .doesNotContain("crypto")
                .doesNotContain("EUR")
                .doesNotContain("50000")
                .doesNotContain("99999")
                .doesNotContain("tx-secret")
                .doesNotContain("SENSITIVE_FLAG")
                .doesNotContain("customerSegment=VIP")
                .doesNotContain("merchantCategory=crypto")
                .doesNotContain("currency=EUR")
                .doesNotContain("raw payload")
                .doesNotContain("debug")
                .doesNotContain("exception");

        assertThat(result.reasonCodes()).isNotEmpty().allSatisfy(reasonCode ->
                assertThat(ReasonCode.known(reasonCode)).isPresent()
        );
        assertThat(result.evidence()).allSatisfy(this::assertSafeEvidenceText);
        assertThat(result.contributions()).allSatisfy(contribution -> {
            assertThat(ReasonCode.known(contribution.feature())).isPresent();
            assertThat(contribution.value()).isNull();
            assertThat(contribution.weight()).isNull();
        });
    }

    private void assertSafeEvidenceText(FraudEngineEvidence evidence) {
        assertThat(ReasonCode.known(evidence.reasonCode())).isPresent();
        assertThat(evidence.title()).hasSizeLessThanOrEqualTo(64);
        assertThat(evidence.description()).hasSizeLessThanOrEqualTo(80);
        assertThat(evidence.title()).doesNotMatch(".*\\s{2,}.*");
        assertThat(evidence.description()).doesNotMatch(".*\\s{2,}.*");
    }

    private ScoringContext context(TransactionEnrichedEvent event) {
        return new ScoringContext(
                event,
                event.featureSnapshot(),
                ScoringMode.RULE_BASED,
                "corr-rule-adapter-test",
                Instant.parse("2026-05-30T10:00:00Z")
        );
    }

    private TransactionEnrichedEvent event(Map<String, Object> featureSnapshot) {
        TransactionEnrichedEvent base = TransactionFixtures.enrichedTransaction().build();
        return new TransactionEnrichedEvent(
                base.eventId(),
                base.transactionId(),
                base.correlationId(),
                base.customerId(),
                base.accountId(),
                base.createdAt(),
                base.transactionTimestamp(),
                new Money(new BigDecimal("50000.00"), "PLN"),
                base.merchantInfo(),
                base.deviceInfo(),
                base.locationInfo(),
                base.customerContext(),
                1,
                "PT1M",
                new Money(new BigDecimal("50000.00"), "PLN"),
                "PT1M",
                0.1d,
                base.merchantFrequency7d(),
                true,
                false,
                false,
                List.of(),
                featureSnapshot
        );
    }

    private String flatten(FraudEngineResult result) {
        return result.reasonCodes() + " " + result.contributions() + " " + result.evidence() + " " + result.statusReason();
    }
}
