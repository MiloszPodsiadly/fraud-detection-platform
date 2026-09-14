package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.common.events.reason.ReasonCode;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleBasedFraudScoringEngineTest {
    private final RuleBasedFraudScoringEngine engine =
            new RuleBasedFraudScoringEngine(new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED));

    @Test
    void scoresCanonicalRulesV2SnapshotAsCurrentPrimaryRuntime() {
        FraudScoreResult result = score(snapshot()
                .with(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5)
                .with(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 5.0d)
                .with(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00"))
                .with(FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN, new BigDecimal("1500.00"))
                .with(FraudFeatureContract.MERCHANT_FREQUENCY_7D, 5)
                .with(FraudFeatureContract.DEVICE_NOVELTY, true)
        );

        assertThat(result.fraudScore()).isEqualTo(0.99d);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(result.modelName()).isEqualTo("rule-based-engine");
        assertThat(result.modelVersion()).isEqualTo("v2");
        assertThat(result.reasonCodes()).containsExactly(
                ReasonCode.DEVICE_NOVELTY.wireValue(),
                ReasonCode.MERCHANT_CONCENTRATION.wireValue(),
                ReasonCode.HIGH_VELOCITY.wireValue(),
                ReasonCode.HIGH_AMOUNT_ACTIVITY.wireValue(),
                ReasonCode.RAPID_PLN_20K_BURST.wireValue(),
                ReasonCode.HIGH_TRANSACTION_AMOUNT.wireValue()
        );
        assertThat(result.scoreDetails().keySet())
                .filteredOn(key -> key.endsWith("RulesV2Weight"))
                .containsExactly(
                        "deviceNoveltyRulesV2Weight",
                        "merchantConcentrationRulesV2Weight",
                        "highVelocityRulesV2Weight",
                        "highAmountActivityRulesV2Weight",
                        "rapidPln20kBurstRulesV2Weight"
                );
    }

    @Test
    void invalidCanonicalFactsFailClosed() {
        TransactionEnrichedEvent invalidCanonical = event(snapshot()
                .with(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5)
                .with(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 4.0d));

        assertThat(RulesV2InputValidator.validate(invalidCanonical).status())
                .isEqualTo(RulesInputValidationStatus.INCONSISTENT_FACTS);
        assertThatThrownBy(() -> engine.score(FraudScoringRequest.from(invalidCanonical)))
                .isInstanceOf(RulesFeatureInputValidationException.class)
                .hasMessage("RULES_FEATURE_INPUT_INVALID")
                .hasMessageNotContaining("5")
                .hasMessageNotContaining("featureSnapshot");
    }

    @Test
    void shouldUseCanonicalReasonCodeTaxonomyInsteadOfRawReasonCodeStrings() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/frauddetection/scoring/service/RuleBasedFraudScoringEngine.java"
        ));

        assertThat(source).doesNotContain("reasonCodes.add(\"");
    }

    @Test
    void scoringEngineDoesNotExposeDetachedValidatedRequestSignature() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/frauddetection/scoring/service/RuleBasedFraudScoringEngine.java"
        ));

        assertThat(source).doesNotContain("scoreValidated(FraudScoringRequest request, RulesInputValidationResult validation)");
        assertThat(source).contains("scoreValidated(RulesV2ValidatedInput input)");
        assertThat(source).doesNotContain("RulesScoringPolicyV1");
        assertThat(source).doesNotContain("RulesV1CompatibilityResolver");
    }

    private FraudScoreResult score(SnapshotBuilder snapshot) {
        return engine.score(FraudScoringRequest.from(event(snapshot)));
    }

    private TransactionEnrichedEvent event(SnapshotBuilder snapshot) {
        TransactionEnrichedEvent base = TransactionFixtures.enrichedTransaction().build();
        return new TransactionEnrichedEvent(
                "evt-v2-primary",
                "txn-v2-primary",
                "corr-v2-primary",
                base.customerId(),
                base.accountId(),
                Instant.parse("2026-06-18T10:00:00Z"),
                Instant.parse("2026-06-18T10:00:00Z"),
                new Money(new BigDecimal("100.00"), "PLN"),
                base.merchantInfo(),
                base.deviceInfo(),
                base.locationInfo(),
                base.customerContext(),
                snapshot.values
        );
    }

    private SnapshotBuilder snapshot() {
        return new SnapshotBuilder(Map.ofEntries(
                Map.entry(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 1),
                Map.entry(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M"),
                Map.entry(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 1.0d),
                Map.entry(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("100.00")),
                Map.entry(FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M"),
                Map.entry(FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN, new BigDecimal("100.00")),
                Map.entry(FraudFeatureContract.MERCHANT_FREQUENCY_7D, 1),
                Map.entry(FraudFeatureContract.DEVICE_NOVELTY, false),
                Map.entry(FraudFeatureContract.COUNTRY_MISMATCH, false),
                Map.entry(FraudFeatureContract.PROXY_OR_VPN_DETECTED, false),
                Map.entry(FraudFeatureContract.CURRENCY, "PLN")
        ));
    }

    private record SnapshotBuilder(Map<String, Object> values) {
        SnapshotBuilder with(String key, Object value) {
            Map<String, Object> updated = new LinkedHashMap<>(values);
            updated.put(key, value);
            return new SnapshotBuilder(Map.copyOf(updated));
        }

    }
}
