package com.frauddetection.scoring.service;

import com.frauddetection.common.events.evidence.ScoringEvidenceStatus;
import com.frauddetection.common.events.evidence.ScoringEvidenceType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.reason.ReasonCode;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedScoringEvidenceProjectionTest {

    private final RuleBasedFraudScoringEngine engine = new RuleBasedFraudScoringEngine(new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED));

    @Test
    void projectsSupportedReasonCodesAsAvailableScoringEvidenceWithoutChangingReasonCodes() {
        var result = engine.score(FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build()));

        assertThat(result.reasonCodes()).contains(ReasonCode.DEVICE_NOVELTY.wireValue(), ReasonCode.HIGH_VELOCITY.wireValue());
        assertThat(result.reasonCodes()).doesNotContain(ReasonCode.UNKNOWN.wireValue());

        Set<String> availableEvidenceReasonCodes = result.scoringEvidence().stream()
                .filter(item -> item.status() == ScoringEvidenceStatus.AVAILABLE)
                .map(item -> item.reasonCode())
                .collect(Collectors.toSet());

        assertThat(availableEvidenceReasonCodes).containsAll(result.reasonCodes());
        assertThat(result.scoringEvidence())
                .allSatisfy(item -> {
                    assertThat(item.evidenceType()).isNotEqualTo(ScoringEvidenceType.DIAGNOSTIC);
                    assertThat(item.attributes().keySet()).noneMatch(key -> key.toLowerCase().contains("customer"));
                    assertThat(item.attributes().keySet()).noneMatch(key -> key.toLowerCase().contains("account"));
                    assertThat(item.title() + " " + item.description())
                            .doesNotContain("confirmed fraud")
                            .doesNotContain("legal proof")
                            .doesNotContain("verdict");
                });
    }

    @Test
    void lowRiskWithoutReasonCodesDoesNotCreateFakeAvailableEvidence() {
        var event = TransactionFixtures.enrichedTransaction().build();
        var lowSignalEvent = new com.frauddetection.common.events.contract.TransactionEnrichedEvent(
                event.eventId(),
                event.transactionId(),
                event.correlationId(),
                event.customerId(),
                event.accountId(),
                event.createdAt(),
                event.transactionTimestamp(),
                new com.frauddetection.common.events.model.Money(new java.math.BigDecimal("45.00"), "USD"),
                event.merchantInfo(),
                event.deviceInfo(),
                event.locationInfo(),
                event.customerContext(),
                java.util.Map.ofEntries(
                        java.util.Map.entry(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 1),
                        java.util.Map.entry(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M"),
                        java.util.Map.entry(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 1.0d),
                        java.util.Map.entry(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new java.math.BigDecimal("45.00")),
                        java.util.Map.entry(FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M"),
                        java.util.Map.entry(FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN, new java.math.BigDecimal("45.00")),
                        java.util.Map.entry(FraudFeatureContract.MERCHANT_FREQUENCY_7D, 1),
                        java.util.Map.entry(FraudFeatureContract.DEVICE_NOVELTY, false),
                        java.util.Map.entry(FraudFeatureContract.COUNTRY_MISMATCH, false),
                        java.util.Map.entry(FraudFeatureContract.PROXY_OR_VPN_DETECTED, false),
                        java.util.Map.entry(FraudFeatureContract.CURRENCY, "USD")
                )
        );

        var result = engine.score(FraudScoringRequest.from(lowSignalEvent));

        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.reasonCodes()).isEmpty();
        assertThat(result.scoringEvidence()).isEmpty();
    }
}
