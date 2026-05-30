package com.frauddetection.scoring.engine.rules;

import com.frauddetection.common.events.engine.FraudEngineEvidence;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.features.FeatureSnapshotReaderFactory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedSignalEngineEvidenceSafetyTest {

    @Test
    void resultExposesOnlyBoundedReasonCodesAndSafeIdentifiers() {
        FraudEngineResult result = new RuleBasedSignalEngine(new FeatureSnapshotReaderFactory()).evaluate(context(Map.of(
                FraudFeatureContract.DEVICE_NOVELTY, true,
                FraudFeatureContract.CUSTOMER_SEGMENT, "VIP",
                FraudFeatureContract.MERCHANT_CATEGORY, "crypto",
                FraudFeatureContract.CURRENCY, "EUR",
                FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN, new BigDecimal("50000"),
                FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN, new BigDecimal("99999"),
                FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, List.of("tx-secret-1"),
                FraudFeatureContract.FEATURE_FLAGS, List.of("SENSITIVE_FLAG")
        )));

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

        Set<String> allowedReasonCodes = java.util.Arrays.stream(RuleBasedSignalReasonCode.values())
                .map(RuleBasedSignalReasonCode::wireValue)
                .collect(Collectors.toSet());
        assertThat(result.reasonCodes()).isNotEmpty().allSatisfy(reasonCode ->
                assertThat(allowedReasonCodes).contains(reasonCode)
        );
        assertThat(result.evidence()).allSatisfy(this::assertSafeEvidenceText);
        assertThat(result.contributions()).allSatisfy(contribution -> {
            assertThat(allowedReasonCodes).contains(contribution.feature());
            assertThat(contribution.value()).isNull();
        });
    }

    private void assertSafeEvidenceText(FraudEngineEvidence evidence) {
        assertThat(evidence.reasonCode()).isIn(java.util.Arrays.stream(RuleBasedSignalReasonCode.values())
                .map(RuleBasedSignalReasonCode::wireValue)
                .toList());
        assertThat(evidence.title()).hasSizeLessThanOrEqualTo(64);
        assertThat(evidence.description()).hasSizeLessThanOrEqualTo(80);
        assertThat(evidence.title()).doesNotMatch(".*\\s{2,}.*");
        assertThat(evidence.description()).doesNotMatch(".*\\s{2,}.*");
    }

    private ScoringContext context(Map<String, Object> featureSnapshot) {
        return new ScoringContext(
                TransactionFixtures.enrichedTransaction().build(),
                featureSnapshot,
                ScoringMode.RULE_BASED,
                "corr-rule-adapter-test",
                Instant.parse("2026-05-30T10:00:00Z")
        );
    }

    private String flatten(FraudEngineResult result) {
        return result.reasonCodes() + " " + result.contributions() + " " + result.evidence() + " " + result.statusReason();
    }
}
