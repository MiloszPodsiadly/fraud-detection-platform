package com.frauddetection.scoring.service;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import org.junit.jupiter.api.Test;

import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceJoinedTestSupport.harness;
import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceJoinedTestSupport.highDiagnosticSummary;
import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceJoinedTestSupport.json;
import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceJoinedTestSupport.timeoutDiagnosticSummary;
import static org.assertj.core.api.Assertions.assertThat;

class ProducerEngineIntelligenceEnabledDiagnosticDisagreementTest {

    @Test
    void diagnosticHighDoesNotOverrideBaselineLow() {
        var harness = harness(true);
        var summary = highDiagnosticSummary();
        harness.stubSuccessfulEnrichment(summary);

        var event = harness.scoreAndCapture();

        assertThat(event.fraudScore()).isEqualTo(harness.baselineResult().fraudScore());
        assertThat(event.riskLevel()).isEqualTo(harness.baselineResult().riskLevel());
        assertThat(event.alertRecommended()).isEqualTo(harness.baselineResult().alertRecommended());
        assertThat(event.reasonCodes()).isEqualTo(harness.baselineResult().reasonCodes());
        assertThat(event.scoringEvidence()).isEqualTo(harness.baselineResult().scoringEvidence());
        assertThat(event.scoreDetails()).isEqualTo(harness.baselineResult().scoreDetails());
        assertThat(event.engineIntelligence()).isEqualTo(summary);
    }

    @Test
    void enabledDiagnosticDisagreementDoesNotCreateDecisioningFields() {
        var harness = harness(true);
        harness.stubSuccessfulEnrichment(highDiagnosticSummary());

        assertThat(json(harness.scoreAndCapture())).doesNotContain(
                "finalDecision",
                "recommendedAction",
                "\"approve\"",
                "\"decline\"",
                "\"block\"",
                "paymentDecision",
                "authorizationDecision",
                "winningEngine",
                "platformRiskScore",
                "platformRiskLevel"
        );
    }

    @Test
    void enabledDiagnosticTimeoutDoesNotBecomeLowRisk() {
        var harness = harness(true);
        harness.stubSuccessfulEnrichment(timeoutDiagnosticSummary());

        var event = harness.scoreAndCapture();
        var timeout = event.engineIntelligence().engines().getFirst();

        assertThat(timeout.status()).isEqualTo(FraudEngineStatus.TIMEOUT);
        assertThat(timeout.riskLevel()).isNull();
        assertThat(timeout.scoreBucket()).isEqualTo(EngineIntelligenceScoreBucket.UNAVAILABLE);
        assertThat(event.riskLevel()).isEqualTo(harness.baselineResult().riskLevel());
        assertThat(json(event)).doesNotContain("\"scoreBucket\":\"LOW\"");
    }
}
